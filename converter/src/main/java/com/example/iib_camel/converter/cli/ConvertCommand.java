package com.example.iib_camel.converter.cli;

import com.example.iib_camel.converter.camel.RouteGenerator;
import com.example.iib_camel.converter.esql.EsqlAssignment;
import com.example.iib_camel.converter.esql.EsqlModule;
import com.example.iib_camel.converter.esql.EsqlParser;
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
 *   iib-camel-converter convert --input &lt;файл|.msgflow-каталог&gt; [--output каталог]
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

    @Option(names = {"--input", "-i"}, required = true,
            description = "Файл .msgflow или каталог (обход рекурсивный).")
    private Path input;

    @Option(names = {"--output", "-o"}, defaultValue = "converted",
            description = "Каталог вывода (по умолчанию converted).")
    private Path output;

    private final List<String> errors = new ArrayList<>();
    private final List<UnitRow> nodes = new ArrayList<>();
    private final List<EsqlUnit> esqlUnits = new ArrayList<>();
    private int convertedFiles;

    /** Единица «узел msgflow» для отчёта (FR-5.1/5.2). */
    record UnitRow(String flowName, String nodeId, String kind, UnitStatus status, String note) {
    }

    /** Единица «модуль ESQL» для отчёта: всегда MANUAL (перевод не автоматизирован). */
    record EsqlUnit(String file, String module, List<String> functions,
                    List<OperatorRow> operators) {
    }

    record OperatorRow(String module, String function, String target) {
    }

    @Override
    public Integer call() {
        List<Path> flows = collect(".msgflow");
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

        for (Path file : flows) {
            try {
                MsgFlow flow = new MsgFlowParser().parse(Files.newInputStream(file));
                RouteResult result = new RouteGenerator(flow).generate();
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

        for (Path file : esqlFiles) {
            try {
                List<EsqlModule> modules = new EsqlParser()
                        .parseAll(Files.readString(file, StandardCharsets.UTF_8));
                esqlUnits.addAll(modules.stream().map(m -> new EsqlUnit(
                        file.getFileName().toString(), m.name(),
                        m.functions().stream().map(f -> f.name()).toList(),
                        m.functions().stream()
                                .flatMap(f -> f.assignments().stream()
                                        .map(a -> new OperatorRow(m.name(), f.name(), a.target())))
                                .toList())).toList());
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

    /** Формирует migration-report.md: полный отчёт FR-5.1 со статами FR-5.2/5.3. */
    private void writeReport() {
        var report = new StringBuilder();
        report.append("# Отчёт о миграции\n\n");
        report.append("Конвертер iib-camel (one-shot CLI), FR-2.1–2.7, FR-3.1, FR-5.1–5.3. Источник: `")
                .append(input).append("`.\n\n");
        report.append("""
                Единицы конвертации: узлы `.msgflow` + модули ESQL. Статусы (FR-5.1):
                `конвертировано` — правило применено полностью; `частично` — элемент создан с TODO-заглушкой
                (нет свойства, узел не подключён к маршруту); `требует ручной доработки` — автоматизация
                на текущем этапе не предусмотрена (код из ESQL, подпотоки); `не поддерживается` — правила
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
                    .append(" | [`").append(name.replace(".msgflow", "")).append("-route.xml`](")
                    .append(name.replace(".msgflow", "")).append("-route.xml) | ")
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

    /** Модули ESQL: всегда «требует ручной доработки» (FR-3.2) + пооператорный разбор (FR-5.2). */
    private void appendEsqlDetails(StringBuilder report) {
        if (esqlUnits.isEmpty()) {
            return;
        }
        report.append("## ESQL\n\n| Файл | Модуль | Функций | Присваиваний (SET) | Статус |\n|---|---|---|---|---|\n");
        for (EsqlUnit u : esqlUnits) {
            report.append("| ").append(u.file()).append(" | ").append(u.module()).append(" | ")
                    .append(u.functions().size()).append(" | ").append(u.operators().size()).append(" | ")
                    .append(UnitStatus.MANUAL.label())
                    .append(" (перевод ESQL → Java не автоматизирован, FR-3.2) |\n");
        }
        report.append("\n### Операторы ESQL\n\n| Файл | Модуль | Функция | SET → цель | Статус |\n|---|---|---|---|---|\n");
        for (EsqlUnit u : esqlUnits) {
            for (OperatorRow op : u.operators()) {
                report.append("| ").append(u.file()).append(" | ").append(u.module()).append(" | ")
                        .append(op.function()).append(" | ").append(op.target()).append(" | ")
                        .append(UnitStatus.MANUAL.label()).append(" |\n");
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
        report.append("## Сводка\n\n")
                .append("- Файлов msgflow: ").append(nodes.stream().map(UnitRow::flowName).distinct().count())
                .append(" (конвертировано ").append(convertedFiles)
                .append(", с ошибками разбора ").append(errors.size()).append(")\n")
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
        System.out.println("Конвертация: файлов " + flowCount + " из " + input + " → " + output
                + "; узлов конвертировано " + counts.getOrDefault(UnitStatus.CONVERTED, 0)
                + ", частично " + counts.getOrDefault(UnitStatus.PARTIAL, 0)
                + ", не поддерживается " + counts.getOrDefault(UnitStatus.NOT_SUPPORTED, 0)
                + (esqlCount == 0 ? ""
                  : "; модулей ESQL " + esqlUnits.size() + " (требует ручной доработки)")
                + "; ошибок " + errors.size());
    }

    private Map<UnitStatus, Integer> countByStatus() {
        Map<UnitStatus, Integer> counts = new LinkedHashMap<>();
        for (UnitStatus s : UnitStatus.values()) {
            counts.put(s, 0);
        }
        nodes.forEach(n -> counts.merge(n.status(), 1, Integer::sum));
        esqlUnits.forEach(u -> counts.merge(UnitStatus.MANUAL, 1, Integer::sum));
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
