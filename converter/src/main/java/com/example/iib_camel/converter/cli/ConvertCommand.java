package com.example.iib_camel.converter.cli;

import com.example.iib_camel.converter.camel.RouteGenerator;
import com.example.iib_camel.converter.diagram.DiagramNames;
import com.example.iib_camel.converter.esql.EsqlFunction;
import com.example.iib_camel.converter.esql.EsqlModule;
import com.example.iib_camel.converter.esql.EsqlParser;
import com.example.iib_camel.converter.esql.EsqlToJavaGenerator;
import com.example.iib_camel.converter.esql.EsqlTranslator;
import com.example.iib_camel.converter.msgflow.MsgFlow;
import com.example.iib_camel.converter.msgflow.MsgFlowParser;
import com.example.iib_camel.converter.report.Diagnosis;
import com.example.iib_camel.converter.report.RouteResult;
import com.example.iib_camel.converter.report.UnitStatus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Команда конвертации потоков IIB в маршруты Apache Camel (FR-2.1–FR-2.7, FR-5.1–FR-5.3, FR-7.1).
 *
 * Контракт:
 *   iib-camel-converter convert [--input &lt;файл|.msgflow-каталог&gt;] [--output каталог]
 *
 * По умолчанию: --input ./input, --output ./output (создаётся при отсутствии) —
 * оба пути относительно текущего каталога, из которого вызван jar (FR-7.1).
 *
 * Конвейер (FR-2.1, Must): парсер → генератор маршрутов → вывод + отчёт.
 * Выход: для каждого .msgflow — файл &lt;Имя&gt;-route.xml; единый migration-report.md.
 * Отчёт (FR-5.1) полный: статусы каждой единицы конвертации (узлы msgflow + модули ESQL),
 * статистика по типам узлов и операторам ESQL, пооператорная детализация (FR-5.2),
 * процент полноты автоматизации (FR-5.3). ESQL честно помечается «требует ручной
 * доработки»: перевод ESQL → код пока не автоматизирован (FR-3.2, Should).
 * Коды возврата: 0 — успех, 2 — частичный успех, 3 — ни один файл.
 */
@Command(name = "convert",
         description = "Конвертация .msgflow (+ESQL) в маршруты Apache Camel с отчётом о миграции.")
public final class ConvertCommand implements Callable<Integer> {

    @Option(names = {"--input", "-i"}, defaultValue = "input",
            description = "Файл .msgflow или каталог (обход рекурсивный); по умолчанию ./input.")
    private Path input;

    @Option(names = {"--output", "-o"}, defaultValue = "output",
            description = "Каталог вывода (создаётся при отсутствии; по умолчанию ./output).")
    private Path output;

    private final List<String> errors = new ArrayList<>();
    private final List<UnitRow> nodes = new ArrayList<>();
    private final List<EsqlUnit> esqlUnits = new ArrayList<>();
    private int convertedFiles;
    private int subflowFiles;

    /** Единица «узел msgflow» для отчёта (FR-5.1/5.2). */
    record UnitRow(String flowName, String nodeId, String kind, UnitStatus status, String note) {
    }

    /** Единица «модуль ESQL» для отчёта (FR-3.2): статус перевода + сгенерированный бин. */
    record EsqlUnit(String file, String module, List<String> functions,
                    List<OperatorRow> operators, UnitStatus status, String note,
                    List<String> issues, List<String> models) {
    }

    /** Единица «оператор ESQL» для отчёта: статус перевода и примечание (FR-5.2). */
    record OperatorRow(String module, String function, String esql, UnitStatus status, String note) {
    }

    @Override
    public Integer call() {
        List<Path> flows = collect(".msgflow");
        List<Path> subflows = collect(".subflow");
        List<Path> esqlFiles = collect(".esql");
        if (flows.isEmpty()) {
            System.err.println("Ошибка: .msgflow-файлы не найдены в " + input);
            return 3;
        }
        try {
            Files.createDirectories(output);
        } catch (IOException e) {
            System.err.println("Ошибка: cannot create output dir " + output + ": " + e.getMessage());
            return 3;
        }

        // Реестр подпотоков (FR-2.2): ссылка в .msgflow резолвится по имени из nsURI
        // ("Check" для Check.subflow; брокер-схема отбрасывается в генераторе).
        Map<String, MsgFlow> subflowRegistry = new LinkedHashMap<>();
        for (Path file : subflows) {
            try {
                MsgFlow sub = new MsgFlowParser().parse(Files.newInputStream(file));
                subflowRegistry.put(sub.name(), sub);
                subflowRegistry.put(DiagramNames.of(sub.name(), stemOf(file)), sub);
            } catch (Exception e) {
                errors.add(file + ": " + e.getMessage());
            }
        }
        subflowFiles = distinctSubflows(subflowRegistry).size();

        for (Path file : flows) {
            try {
                MsgFlow flow = new MsgFlowParser().parse(Files.newInputStream(file));
                RouteResult result = new RouteGenerator(flow, subflowRegistry).generate();
                String routeId = flow.name().replaceAll("\\.msgflow$", "");
                Path routeFile = output.resolve(routeId + "-route.xml");
                Files.writeString(routeFile, result.xml(), StandardCharsets.UTF_8);
                convertedFiles++;
                for (Diagnosis d : result.diagnoses()) {
                    nodes.add(new UnitRow(flow.name(), d.nodeId(), d.kind(), d.status(), d.note()));
                }
            } catch (Exception e) {
                errors.add(file + ": " + e.getMessage());
            }
        }

        // Юниты-подпотоки: диагнозы при разворачивании — обход от Input-узлов
        // (eflow:FCMSource); отдельного route-файла не создаётся — подпоток вызван
        // через direct: из родительского маршрута (FR-2.2).
        for (MsgFlow sub : distinctSubflows(subflowRegistry)) {
            RouteResult result = new RouteGenerator(sub, subflowRegistry).inlineResult();
            for (Diagnosis d : result.diagnoses()) {
                nodes.add(new UnitRow(sub.name(), d.nodeId(), d.kind(), d.status(), d.note()));
            }
        }

        // Перевод ESQL → Java-бины (FR-3.2): модуль → beans/<Class>.java (исходник без компиляции),
        // честные статусы: все операторы переведены → конвертировано; часть → частично (FR-3.3);
        // ни одного/нет Main → требует ручной доработки. Ничего не теряется молча (FR-5.1–5.3).
        EsqlToJavaGenerator generator = new EsqlToJavaGenerator();
        Path beansDir = output.resolve("beans");
        for (Path file : esqlFiles) {
            try {
                List<EsqlModule> modules = new EsqlParser()
                        .parseAll(Files.readString(file, StandardCharsets.UTF_8));
                for (EsqlModule m : modules) {
                    List<OperatorRow> operators = new ArrayList<>();
                    UnitStatus unitStatus = UnitStatus.MANUAL;
                    String unitNote = "перевод не выполнен";
                    List<String> issues = List.of();
                    EsqlToJavaGenerator.BeanResult bean = null;
                    try {
                        bean = generator.generate(m);
                        issues = List.copyOf(bean.issues());
                        Files.createDirectories(beansDir);
                        Files.writeString(beansDir.resolve(bean.className() + ".java"),
                                bean.java(), StandardCharsets.UTF_8);
                        // POJO-модели тела (FR-3.4, первый уровень) — соседний каталог models/
                        for (EsqlToJavaGenerator.ModelResult model : bean.models()) {
                            Files.createDirectories(output.resolve("models"));
                            Files.writeString(
                                    output.resolve("models").resolve(model.className() + ".java"),
                                    model.java(), StandardCharsets.UTF_8);
                        }
                        for (EsqlTranslator.StatementTranslation st : bean.statements()) {
                            operators.add(new OperatorRow(
                                    m.name(), "Main", st.esql(), st.status(), st.note()));
                        }
                        long convertedOps = bean.statements().stream()
                                .filter(st -> st.status() == UnitStatus.CONVERTED).count();
                        if (bean.complete()) {
                            unitStatus = UnitStatus.CONVERTED;
                            unitNote = "бин `" + bean.beanId() + "` → `beans/"
                                    + bean.className() + ".java`";
                        } else if (convertedOps > 0) {
                            unitStatus = UnitStatus.PARTIAL;
                            unitNote = "частично (" + convertedOps + " из " + bean.statements().size()
                                    + " операторов) — бин `beans/" + bean.className() + ".java`";
                        } else {
                            unitStatus = UnitStatus.MANUAL;
                            unitNote = "операторы вне переводимого подмножества: "
                                    + bean.issues().get(0);
                        }
                    } catch (IllegalArgumentException e) {
                        unitStatus = UnitStatus.MANUAL;
                        unitNote = e.getMessage();
                    }
                    esqlUnits.add(new EsqlUnit(file.getFileName().toString(), m.name(),
                            m.functions().stream().map(EsqlFunction::name).toList(),
                            List.copyOf(operators), unitStatus, unitNote, issues,
                            bean == null ? List.of()
                                    : bean.models().stream().map(x -> "models/" + x.className() + ".java")
                                            .toList()));
                }
            } catch (Exception e) {
                errors.add(file + ": " + e.getMessage());
            }
        }

        writeReport();
        printSummary(flows.size(), esqlFiles.size());

        if (errors.size() == flows.size()) {
            return 3;
        }
        return errors.isEmpty() ? 0 : 2;
    }

    /** Реестр содержит два ключа на файл (полное имя и короткое) — уникализуем по порядку вставки. */
    private static List<MsgFlow> distinctSubflows(Map<String, MsgFlow> registry) {
        List<MsgFlow> unique = new ArrayList<>();
        for (MsgFlow sub : registry.values()) {
            if (unique.stream().noneMatch(s -> s == sub || s.name().equals(sub.name()))) {
                unique.add(sub);
            }
        }
        return unique;
    }

    private static String stemOf(Path file) {
        String name = file.getFileName() == null ? "flow" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /** Формирует migration-report.md: полный отчёт FR-5.1 со статами FR-5.2/5.3. */
    private void writeReport() {
        var report = new StringBuilder();
        report.append("# Отчёт о миграции\n\n");
        report.append("Конвертер iib-camel (one-shot CLI), FR-2.1–2.7, FR-3.1, FR-5.1–5.3. Источник: `")
                .append(input).append("`.\n\n");
        report.append("""
                Единицы конвертации: узлы `.msgflow` + подпотоки + модули ESQL. Статусы (FR-5.1):
                `конвертировано` — правило применено полностью; `частично` — элемент создан с TODO-заглушкой
                (непереводимые операторы ESQL, нет свойства, узел не подключён к маршруту); `требует
                ручной доработки` — автоматизация не предусмотрена; `не поддерживается` — правила
                конвертации нет, создана TODO-заглушка (FR-2.7).
                \n""");
        appendUnitStats(report);
        appendFlowTable(report);
        appendKindStats(report);
        appendNodeDetails(report);
        appendEsqlDetails(report);
        appendErrors(report);
        appendTotals(report);

        try {
            Files.writeString(output.resolve("migration-report.md"), report.toString(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            errors.add(output.resolve("migration-report.md") + ": " + e.getMessage());
        }
    }

    /** Сводка статусов по всем единицам (узлы + модули ESQL) — FR-5.1. */
    private void appendUnitStats(StringBuilder report) {
        Map<UnitStatus, Integer> counts = countByStatus();
        long total = totalUnits();
        report.append("## Статусы единиц\n\n| Статус | Единиц | % |\n|---|---|---|\n");
        for (UnitStatus status : UnitStatus.values()) {
            report.append("| ").append(status.label()).append(" | ")
                    .append(counts.getOrDefault(status, 0)).append(" | ")
                    .append(percent(counts.getOrDefault(status, 0), total)).append(" |\n");
        }
        report.append("| **Всего** | ").append(total).append(" | 100.0 |\n\n");
    }

    /** Таблица потоков: результат + статистика узлов по статусам. */
    private void appendFlowTable(StringBuilder report) {
        report.append("## Потоки\n\n| Поток | Результат | Узлов | Конв. | Част. | Руч. | Не под. |\n|---|---|---|---|---|---|---|\n");
        List<String> flowNames = nodes.stream().map(UnitRow::flowName).distinct().toList();
        for (String name : flowNames) {
            // маршрут есть только у .msgflow; подпотоки вызываются direct: из родительского
            boolean isSubflow = name.endsWith(".subflow");
            String link = isSubflow
                    ? "direct: (вызов из родительского маршрута)"
                    : "[`" + name.replace(".msgflow", "") + "-route.xml`](" + name.replace(".msgflow", "") + "-route.xml)";
            Map<UnitStatus, Long> by = new LinkedHashMap<>();
            long nodeTotal = 0;
            for (UnitStatus status : UnitStatus.values()) {
                by.put(status, 0L);
            }
            for (UnitRow row : nodes) {
                if (row.flowName().equals(name)) {
                    nodeTotal++;
                    by.merge(row.status(), 1L, Long::sum);
                }
            }
            report.append("| ").append(name)
                    .append(" | ").append(link).append(" | ")
                    .append(nodeTotal).append(" | ")
                    .append(by.get(UnitStatus.CONVERTED)).append(" | ")
                    .append(by.get(UnitStatus.PARTIAL)).append(" | ")
                    .append(by.get(UnitStatus.MANUAL)).append(" | ")
                    .append(by.get(UnitStatus.NOT_SUPPORTED)).append(" |\n");
        }
        report.append("\n");
    }

    /** Статистика по типам узлов (FR-5.1: «количеству узлов/операторов каждого типа»). */
    private void appendKindStats(StringBuilder report) {
        List<String> kinds = nodes.stream().map(UnitRow::kind).distinct().sorted().toList();
        report.append("## Узлы по типам\n\n| Тип узла | Всего | Конв. | Част. | Не под. |\n|---|---|---|---|---|\n");
        for (String kind : kinds) {
            List<UnitRow> of = nodes.stream().filter(n -> n.kind().equals(kind)).toList();
            report.append("| ").append(kind).append(" | ").append(of.size()).append(" | ")
                    .append(count(of, UnitStatus.CONVERTED)).append(" | ")
                    .append(count(of, UnitStatus.PARTIAL)).append(" | ")
                    .append(count(of, UnitStatus.NOT_SUPPORTED)).append(" |\n");
        }
        report.append("\n");
    }

    /** Пооператорная детализация узлов: id, тип, статус, цель/причина (FR-5.2). */
    private void appendNodeDetails(StringBuilder report) {
        report.append("## Детализация узлов\n\n");
        for (String name : nodes.stream().map(UnitRow::flowName).distinct().toList()) {
            report.append("### ").append(name).append("\n\n")
                  .append("| Узел | Тип | Статус | Примечание |\n|---|---|---|---|\n");
            nodes.stream().filter(n -> n.flowName().equals(name))
                    .forEach(n -> report.append("| ").append(n.nodeId()).append(" | ").append(n.kind())
                            .append(" | ").append(n.status().label()).append(" | ")
                            .append(n.note()).append(" |\n"));
            report.append("\n");
        }
    }

    /** Модули ESQL: статус автоматического перевода (FR-3.2) + пооператорный разбор (FR-5.2). */
    private void appendEsqlDetails(StringBuilder report) {
        if (esqlUnits.isEmpty()) {
            return;
        }
        report.append("## ESQL\n\nМодули переведены подмножеством (FR-3.2): `SET OutputRoot = InputRoot`,"
                + "`MQMD-поля`, `строковые поля тела`, `CAST-литералы`; ветвления и вызовы — TODO в бине"
                + " (FR-3.3).\n\n")
                .append("| Файл | Модуль | Функций | Присваиваний (SET) | Статус | Примечание |\n|---|---|---|---|---|---|\n");
        for (EsqlUnit u : esqlUnits) {
            report.append("| ").append(u.file()).append(" | ").append(u.module()).append(" | ")
                    .append(u.functions().size()).append(" | ").append(u.operators().size()).append(" | ")
                    .append(u.status().label()).append(" | ")
                    .append(u.status() == UnitStatus.CONVERTED
                            ? u.note()
                            : u.note() + " (FR-3.3)").append(" |\n");
        }
        report.append("\n### Операторы ESQL\n\n| Файл | Модуль | Оператор | Статус | Примечание |\n|---|---|---|---|---|\n");
        for (EsqlUnit u : esqlUnits) {
            for (OperatorRow op : u.operators()) {
                report.append("| ").append(u.file()).append(" | ").append(op.module()).append(" | `")
                        .append(op.esql()).append("` | ")
                        .append(op.status().label()).append(" | ")
                        .append(op.note() == null ? "" : op.note()).append(" |\n");
            }
            if (!u.issues().isEmpty()) {
                report.append("\nНепереводимые конструкции `").append(u.module()).append("`:\n\n");
                u.issues().forEach(i -> report.append("- ").append(i).append('\n'));
                report.append('\n');
            }
            if (!u.models().isEmpty()) {
                report.append("\nPOJO-модели тела (FR-3.4, первый уровень) для `").append(u.module())
                        .append("`:\n\n");
                u.models().forEach(m -> report.append("- `").append(m).append("`\n"));
                report.append('\n');
            }
        }
        report.append("\n");
    }

    private void appendErrors(StringBuilder report) {
        if (errors.isEmpty()) {
            return;
        }
        report.append("## Ошибки\n\n");
        errors.forEach(e -> report.append("- `").append(e).append("`\n"));
        report.append("\n");
    }

    /** Сводка: файлы, единицы по статусам, процент полноты автоматизации (FR-5.3). */
    private void appendTotals(StringBuilder report) {
        Map<UnitStatus, Integer> counts = countByStatus();
        long total = totalUnits();
        long manual = counts.getOrDefault(UnitStatus.MANUAL, 0);
        long unsupported = counts.getOrDefault(UnitStatus.NOT_SUPPORTED, 0);
        long partial = counts.getOrDefault(UnitStatus.PARTIAL, 0);
        long converted = counts.getOrDefault(UnitStatus.CONVERTED, 0);
        long attention = manual + unsupported + partial;
        long msgflowCount = nodes.stream().map(UnitRow::flowName)
                .filter(n -> n.endsWith(".msgflow")).distinct().count();
        report.append("## Сводка\n\n")
                .append("- Файлов msgflow: ").append(msgflowCount)
                .append(" (конвертировано ").append(convertedFiles)
                .append(", с ошибками разбора ").append(errors.size()).append(")\n")
                .append("- Подпотоков: ").append(subflowFiles)
                .append(" (развёрнуты во вложенные маршруты direct:, FR-2.2)\n")
                .append("- Единиц конвертации: ").append(total)
                .append(" (конвертировано ").append(converted)
                .append(", частично ").append(partial)
                .append(", требует ручной доработки ").append(manual)
                .append(", не поддерживается ").append(unsupported).append(")\n")
                .append("- Требуют внимания: ").append(attention).append(" из ").append(total).append(" единиц; ")
                .append("полнота автоматизации: ").append(percent(converted, total))
                .append("% конвертировано (FR-5.3)\n\n");
    }

    private void printSummary(int flowCount, int esqlCount) {
        Map<UnitStatus, Integer> counts = countByStatus();
        long esqlConverted = esqlUnits.stream().filter(u -> u.status() == UnitStatus.CONVERTED).count();
        long esqlPartial = esqlUnits.stream().filter(u -> u.status() == UnitStatus.PARTIAL).count();
        long esqlManual = esqlUnits.size() - esqlConverted - esqlPartial;
        System.out.println("Конвертация: файлов " + flowCount + " из " + input + " → " + output
                + "; узлов конвертировано " + counts.getOrDefault(UnitStatus.CONVERTED, 0)
                + ", частично " + counts.getOrDefault(UnitStatus.PARTIAL, 0)
                + ", не поддерживается " + counts.getOrDefault(UnitStatus.NOT_SUPPORTED, 0)
                + (subflowFiles == 0 ? "" : "; подпотоков " + subflowFiles + " (direct:)")
                + (esqlCount == 0 ? ""
                  : "; модулей ESQL " + esqlUnits.size() + " (конвертировано "
                    + esqlConverted + ", частично " + esqlPartial + ", ручных " + esqlManual + ")")
                + "; ошибок " + errors.size());
    }

    private Map<UnitStatus, Integer> countByStatus() {
        Map<UnitStatus, Integer> counts = new LinkedHashMap<>();
        for (UnitStatus s : UnitStatus.values()) {
            counts.put(s, 0);
        }
        nodes.forEach(n -> counts.merge(n.status(), 1, Integer::sum));
        esqlUnits.forEach(u -> counts.merge(u.status(), 1, Integer::sum));
        return counts;
    }

    private long totalUnits() {
        return nodes.size() + esqlUnits.size();
    }

    private long count(List<UnitRow> rows, UnitStatus status) {
        return rows.stream().filter(r -> r.status() == status).count();
    }

    private static String percent(long part, long total) {
        return total == 0 ? "0.0" : String.format(Locale.ROOT, "%.1f", part * 100.0 / total);
    }

    private List<Path> collect(String extension) {
        try (Stream<Path> walk = Files.walk(input)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(extension))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
