package ru.petrovov.iib_camel.converter.cli;

import ru.petrovov.iib_camel.converter.camel.RouteGenerator;
import ru.petrovov.iib_camel.converter.esql.EsqlFunction;
import ru.petrovov.iib_camel.converter.esql.EsqlModule;
import ru.petrovov.iib_camel.converter.esql.EsqlParser;
import ru.petrovov.iib_camel.converter.esql.EsqlToJavaGenerator;
import ru.petrovov.iib_camel.converter.esql.EsqlTranslator;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlow;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlowParser;
import ru.petrovov.iib_camel.converter.project.MappingProject;
import ru.petrovov.iib_camel.converter.report.Diagnosis;
import ru.petrovov.iib_camel.converter.report.RouteResult;
import ru.petrovov.iib_camel.converter.report.UnitStatus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(name = "convert",
         description = "Конвертация .msgflow (+ESQL) в маршруты Apache Camel с отчётом о миграции.")
public final class ConvertCommand implements Callable<Integer> {

    @Option(names = {"--input", "-i"}, defaultValue = "input",
            description = "Файл .msgflow или каталог (обход рекурсивный); по умолчанию ./input.")
    private Path input;

    @Option(names = {"--output", "-o"}, defaultValue = "output",
            description = "Каталог вывода (создаётся при отсутствии; по умолчанию ./output).")
    private Path output;

    @Option(names = "--no-strict",
            description = "Не блокировать весь пакет; отдельные неподдерживаемые узлы остаются fail-fast.")
    private boolean noStrict;

    private final List<String> errors = new ArrayList<>();
    private final List<UnitRow> nodes = new ArrayList<>();
    private final List<EsqlUnit> esqlUnits = new ArrayList<>();
    private final List<ComputeReference> computeReferences = new ArrayList<>();
    private int convertedFiles;
    private int subflowFiles;
    private ArtifactOutput artifacts;
    private final Map<String, String> routeFiles = new LinkedHashMap<>();
    private final Map<String, String> generatedSources = new LinkedHashMap<>();
    private MappingProject mappingProject;

    record UnitRow(String flowName, String nodeId, String kind, UnitStatus status, String note) {
    }

    record EsqlUnit(String file, String schema, String module, List<String> functions,
                    List<OperatorRow> operators, UnitStatus status, String note,
                    List<String> issues, List<String> models) {
    }

    record OperatorRow(String module, String function, String esql, UnitStatus status, String note) {
    }

    record ComputeReference(String flowName, String nodeId, String expression) {}

    record SourceFlow(Path file, MsgFlow flow) {}
    record EsqlJob(Path file, EsqlModule module, EsqlToJavaGenerator.BeanResult bean, String issue) {}

    @Override
    public Integer call() {
        errors.clear();
        nodes.clear();
        esqlUnits.clear();
        computeReferences.clear();
        routeFiles.clear();
        generatedSources.clear();
        convertedFiles = 0;
        subflowFiles = 0;
        artifacts = new ArtifactOutput(output, "convert");
        List<Path> files = collect(".msgflow");
        List<Path> esqlFiles = collect(".esql");
        if (files.isEmpty()) {
            System.err.println("Ошибка: .msgflow-файлы не найдены в " + input);
            return 3;
        }
        mappingProject = MappingProject.load(input);
        errors.addAll(mappingProject.errors());
        List<SourceFlow> flows = readFlows(files, ".msgflow");
        List<SourceFlow> subflows = readFlows(collect(".subflow"), ".subflow");
        subflowFiles = subflows.size();
        var generator = new EsqlToJavaGenerator();
        List<EsqlJob> jobs = readEsql(esqlFiles, generator);
        boolean valid = validateIdentities(flows, subflows, jobs);
        var pathIssues = mappingProject.validateBodyPaths(jobs.stream().filter(j -> j.bean() != null)
                .flatMap(j -> j.bean().statements().stream()).toList());
        if (!pathIssues.isEmpty()) { errors.addAll(pathIssues); valid = false; }
        List<EsqlToJavaGenerator.ModelResult> models = List.of();
        try {
            models = generator.generateModels(jobs.stream().filter(j -> j.bean() != null)
                    .flatMap(j -> j.bean().statements().stream()).toList());
        } catch (IllegalArgumentException e) {
            errors.add("модели: " + e.getMessage());
            valid = false;
        }
        Map<String, MsgFlow> registry = subflowRegistry(subflows);
        Set<String> emittedSubflows = new LinkedHashSet<>();
        for (SourceFlow source : flows) {
            MsgFlow flow = source.flow();
            collectComputeReferences(flow);
            if (!valid) {
                rejectFlow(flow);
                continue;
            }
            try {
                Set<String> pendingSubflows = new LinkedHashSet<>(emittedSubflows);
                RouteResult result = new RouteGenerator(flow, registry, pendingSubflows).generate();
                String name = ArtifactOutput.fileStem(baseName(flow.name())) + "-route.xml";
                generatedSources.put(name, result.xml());
                routeFiles.put(flow.name(), name);
                emittedSubflows.addAll(pendingSubflows);
                convertedFiles++;
                appendDiagnoses(flow, result);
            } catch (Exception e) {
                errors.add(source.file() + ": " + e.getMessage());
                rejectFlow(flow);
            }
        }
        for (SourceFlow source : subflows) {
            MsgFlow sub = source.flow();
            collectComputeReferences(sub);
            if (!valid) {
                rejectFlow(sub);
                continue;
            }
            try {
                appendDiagnoses(sub, new RouteGenerator(sub, registry).inlineResult());
            } catch (Exception e) {
                errors.add(source.file() + ": " + e.getMessage());
                rejectFlow(sub);
            }
        }
        for (EsqlJob job : jobs) appendEsql(job, valid);
        if (valid) {
            var schemaSources = mappingProject.schemas().javaSources();
            for (var model : models) {
                if (!schemaSources.containsKey(model.className())) generatedSources.put("models/" + model.className() + ".java", model.java());
            }
            schemaSources.forEach((name, java) -> generatedSources.put("models/" + name + ".java", java));
            for (var mapper : mappingProject.mappers()) {
                generatedSources.put("mapping/" + mapper.className() + ".java", mapper.javaSource());
                generatedSources.put("mapping/" + mapper.processorClassName() + ".java", mapper.processorJava());
            }
        }
        validateComputeReferences();
        boolean ready = errors.isEmpty() && nodes.stream().allMatch(n -> n.status() == UnitStatus.CONVERTED)
                && esqlUnits.stream().allMatch(e -> e.status() == UnitStatus.CONVERTED);
        if (!noStrict && !ready) {
            generatedSources.replaceAll((name, java) -> name.endsWith("-route.xml")
                    ? java.replaceAll("(<from\\b[^>]*?/>)", "$1\n    <throwException exceptionType=\"java.lang.UnsupportedOperationException\" message=\"Strict validation failed: see migration-report.md\"/>")
                    : java);
        }
        generatedSources.forEach(artifacts::stage);
        writeReport();
        try {
            artifacts.publish();
        } catch (IOException | IllegalArgumentException e) {
            errors.add("вывод не опубликован: " + e.getMessage());
            System.err.println(errors.getLast());
            printSummary(files.size(), esqlFiles.size());
            return 3;
        }
        printSummary(files.size(), esqlFiles.size());
        return convertedFiles == 0 ? 3 : !errors.isEmpty() || !noStrict && !ready ? 2 : 0;
    }

    private List<SourceFlow> readFlows(List<Path> files, String extension) {
        var result = new ArrayList<SourceFlow>();
        for (Path file : files) {
            try (var in = Files.newInputStream(file)) {
                MsgFlow flow = new MsgFlowParser().parse(in);
                if (flow.name().isBlank()) flow = new MsgFlow(stemOf(file) + extension, flow.nodes(), flow.connections());
                result.add(new SourceFlow(file, mappingProject.bind(flow)));
            } catch (Exception e) { errors.add(file + ": " + e.getMessage()); }
        }
        return result;
    }

    private List<EsqlJob> readEsql(List<Path> files, EsqlToJavaGenerator generator) {
        var result = new ArrayList<EsqlJob>();
        for (Path file : files) {
            try {
                for (EsqlModule module : new EsqlParser().parseAll(Files.readString(file))) {
                    try { result.add(new EsqlJob(file, module, generator.generate(module), null)); }
                    catch (IllegalArgumentException e) { result.add(new EsqlJob(file, module, null, e.getMessage())); }
                }
            } catch (Exception e) { errors.add(file + ": " + e.getMessage()); }
        }
        return result;
    }

    private boolean validateIdentities(List<SourceFlow> flows, List<SourceFlow> subs, List<EsqlJob> jobs) {
        int before = errors.size();
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (SourceFlow source : flows) {
            String base = baseName(source.flow().name());
            try {
                reserve(names, ArtifactOutput.fileStem(base).toLowerCase(Locale.ROOT) + "-route.xml", source.file().toString());
            } catch (IllegalArgumentException e) { errors.add(source.file() + ": " + e.getMessage()); }
            var inputs = source.flow().nodes().stream().filter(n -> n.kind().equals("MQInput")).toList();
            for (var node : inputs) reserve(ids, inputs.size() == 1 ? base : base + "-" + node.id(), source.file().toString());
        }
        for (SourceFlow source : subs) {
            String base = baseName(source.flow().name());
            try { ArtifactOutput.fileStem(base); }
            catch (IllegalArgumentException e) { errors.add(source.file() + ": " + e.getMessage()); }
            reserve(ids, base, source.file().toString());
        }
        java.util.stream.Stream.concat(flows.stream(), subs.stream()).forEach(source -> {
            for (var node : source.flow().nodes()) {
                if (!node.kind().equals("Subflow")) continue;
                String requested = baseName(node.properties().getOrDefault("subflowName", ""));
                List<Path> candidates = subs.stream().filter(sub -> baseName(sub.flow().name()).equals(requested)
                                || stemOf(sub.file()).equals(requested))
                        .map(SourceFlow::file).distinct().toList();
                if (candidates.size() > 1) errors.add(source.file() + ": неоднозначная ссылка на подпоток "
                        + requested + " у узла " + node.id() + ": " + candidates);
            }
        });
        Map<String, String> beans = new LinkedHashMap<>();
        int index = 0;
        for (EsqlJob job : jobs) {
            if (job.bean() != null) {
                String owner = job.file() + "#" + job.module().schema() + "." + job.module().name() + "@" + index;
                reserve(beans, job.bean().beanId(), owner);
                reserve(names, "beans/" + job.bean().className().toLowerCase(Locale.ROOT) + ".java", owner);
            }
            index++;
        }
        for (var mapper : mappingProject.mappers()) {
            String owner = mapper.source().toString();
            reserve(beans, mapper.beanName(), owner);
            reserve(beans, mapper.mapperBeanName(), owner);
            reserve(names, "mapping/" + mapper.className().toLowerCase(Locale.ROOT) + ".java", owner);
            reserve(names, "mapping/" + mapper.processorClassName().toLowerCase(Locale.ROOT) + ".java", owner);
        }
        return errors.size() == before;
    }

    private void reserve(Map<String, String> used, String id, String owner) {
        String previous = used.putIfAbsent(id, owner);
        if (previous != null) errors.add("конфликт идентификатора " + id + ": " + previous + " и " + owner);
    }

    private static String baseName(String name) {
        return name.replaceFirst("(?i)\\.(msgflow|subflow)$", "");
    }

    private static Map<String, MsgFlow> subflowRegistry(List<SourceFlow> sources) {
        Map<String, MsgFlow> registry = new LinkedHashMap<>();
        Map<String, List<MsgFlow>> aliases = new LinkedHashMap<>();
        for (SourceFlow source : sources) {
            registry.put(source.flow().name(), source.flow());
            registry.put(baseName(source.flow().name()), source.flow());
            aliases.computeIfAbsent(stemOf(source.file()), k -> new ArrayList<>()).add(source.flow());
        }
        aliases.forEach((alias, candidates) -> {
            if (candidates.size() == 1) registry.putIfAbsent(alias, candidates.getFirst());
        });
        return registry;
    }

    private void appendDiagnoses(MsgFlow flow, RouteResult result) {
        result.diagnoses().forEach(d -> nodes.add(new UnitRow(flow.name(), d.nodeId(), d.kind(), d.status(), d.note())));
        result.diagnoses().stream().filter(d -> d.note().startsWith("граф не преобразован:"))
                .map(d -> flow.name() + ": " + d.note()).distinct().forEach(errors::add);
    }

    private void rejectFlow(MsgFlow flow) {
        flow.nodes().forEach(n -> nodes.add(new UnitRow(flow.name(), n.id(), n.kind(), UnitStatus.MANUAL,
                "артефакт не сформирован: см. ошибки пакета")));
    }

    private void appendEsql(EsqlJob job, boolean valid) {
        var bean = job.bean();
        var operators = new ArrayList<OperatorRow>();
        UnitStatus status = UnitStatus.MANUAL;
        String note = job.issue();
        List<String> issues = bean == null ? List.of() : bean.issues();
        if (bean != null) {
            for (var statement : bean.statements()) operators.add(new OperatorRow(job.module().name(), "Main",
                    statement.esql(), statement.status(), statement.note()));
            if (valid) {
                generatedSources.put("beans/" + bean.className() + ".java", bean.java());
                long converted = bean.statements().stream().filter(s -> s.status() == UnitStatus.CONVERTED).count();
                status = bean.complete() ? UnitStatus.CONVERTED : converted > 0 ? UnitStatus.PARTIAL : UnitStatus.MANUAL;
                note = bean.complete() ? "бин `" + bean.beanId() + "` → `beans/" + bean.className() + ".java`"
                        : "перевод неполный — бин `beans/" + bean.className() + ".java` блокирует исполнение";
            } else note = "артефакт не сформирован: конфликт выходных имён/моделей";
        }
        esqlUnits.add(new EsqlUnit(job.file().getFileName().toString(), job.module().schema(), job.module().name(),
                job.module().functions().stream().map(EsqlFunction::name).toList(), List.copyOf(operators), status,
                note, issues, bean == null || !valid ? List.of() : bean.models().stream().map(m -> "models/" + m.className() + ".java").toList()));
    }

    private void collectComputeReferences(MsgFlow flow) {
        flow.nodes().stream().filter(n -> n.kind().equals("Compute"))
                .forEach(n -> computeReferences.add(new ComputeReference(flow.name(), n.id(),
                        n.properties().getOrDefault("computeExpression", ""))));
    }

    private void validateComputeReferences() {
        for (ComputeReference reference : computeReferences) {
            String expression = reference.expression();
            int hash = expression.indexOf('#');
            UnitStatus status = UnitStatus.MANUAL;
            String note = "неразрешённая Compute-ссылка: " + expression;
            if (hash >= 0) {
                String routine = expression.substring(hash + 1);
                int dot = routine.indexOf('.');
                String module = dot < 0 ? routine : routine.substring(0, dot);
                String function = dot < 0 ? "Main" : routine.substring(dot + 1);
                String schema = expression.substring(0, hash).replaceFirst("^esql://routine/", "");
                List<EsqlUnit> matches = esqlUnits.stream()
                        .filter(u -> u.module().equals(module))
                        .filter(u -> schema.isBlank() || u.schema().equals(schema))
                        .toList();
                if (matches.size() == 1 && function.equalsIgnoreCase("Main")) {
                    EsqlUnit unit = matches.getFirst();
                    status = unit.status();
                    note = status == UnitStatus.CONVERTED ? null
                            : "Compute-модуль не готов к исполнению: " + unit.note();
                } else if (matches.size() > 1) {
                    note = "неоднозначная Compute-ссылка: " + expression;
                } else if (!function.equalsIgnoreCase("Main")) {
                    note = "Compute-функция не поддерживается: " + function;
                }
            }
            if (note != null) {
                for (int i = 0; i < nodes.size(); i++) {
                    UnitRow row = nodes.get(i);
                    if (row.flowName().equals(reference.flowName()) && row.nodeId().equals(reference.nodeId())) {
                        nodes.set(i, new UnitRow(row.flowName(), row.nodeId(), row.kind(),
                                UnitStatus.worst(row.status(), status), row.note() + "; " + note));
                    }
                }
            }
        }
    }

    private static String stemOf(Path file) {
        String name = file.getFileName() == null ? "flow" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private void writeReport() {
        var report = new StringBuilder();
        report.append("# Отчёт о миграции\n\n");
        boolean ready = errors.isEmpty() && nodes.stream().allMatch(n -> n.status() == UnitStatus.CONVERTED)
                && esqlUnits.stream().allMatch(e -> e.status() == UnitStatus.CONVERTED);
        report.append("Готовность пакета в профиле пилота: **").append(ready ? "да" : "нет")
                .append("**. Строгая проверка: ").append(noStrict ? "отключена" : "включена")
                .append(".\n\n");
        if (!noStrict && !ready) report.append("Все маршруты пакета заблокированы до бизнес-операций.\n\n");
        report.append(mappingProject.markdown()).append('\n');
        report.append("Конвертер iib-camel (one-shot CLI), FR-2.1–2.7, FR-3.1, FR-5.1–5.3. Источник: `")
                .append(input).append("`.\n\n");
        report.append("""
                Единицы конвертации: узлы `.msgflow` + подпотоки + модули ESQL. Статусы (FR-5.1):
                `конвертировано` — правило применено полностью; `частично` — элемент требует доработки; неполный ESQL-бин блокирует исполнение
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

        artifacts.stage("migration-report.md", report.toString());
    }

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

    private void appendFlowTable(StringBuilder report) {
        report.append("## Потоки\n\n| Поток | Результат | Узлов | Конв. | Част. | Руч. | Не под. |\n|---|---|---|---|---|---|---|\n");
        List<String> flowNames = nodes.stream().map(UnitRow::flowName).distinct().toList();
        for (String name : flowNames) {
            boolean isSubflow = name.endsWith(".subflow");
            String link = isSubflow
                    ? "direct: (вызов из родительского маршрута)"
                    : routeFiles.containsKey(name) ? "[`" + routeFiles.get(name) + "`](" + routeFiles.get(name) + ")" : "не сформирован";
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
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
