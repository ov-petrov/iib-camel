package ru.petrovov.iib_camel.converter.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import ru.petrovov.iib_camel.converter.diagram.*;
import ru.petrovov.iib_camel.converter.esql.EsqlInventoryRenderer;
import ru.petrovov.iib_camel.converter.esql.EsqlModule;
import ru.petrovov.iib_camel.converter.esql.EsqlParser;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlow;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlowParser;
import ru.petrovov.iib_camel.converter.project.MappingProject;
import ru.petrovov.iib_camel.converter.project.ProjectValidation;
import ru.petrovov.iib_camel.converter.report.UnitStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(name = "analyze",
         description = "Инвентаризация системы IIB до миграции: схема + отчёт «как есть».")
public final class AnalyzeCommand implements Callable<Integer> {

    private final MsgFlowParser parser = new MsgFlowParser();
    private final MermaidDiagramRenderer mermaidRenderer =
            new MermaidDiagramRenderer(NodeCategoryRegistry.withIibDefaults());
    private final AsciiDocDiagramRenderer adocRenderer = new AsciiDocDiagramRenderer();
    private final AnalysisMarkdownRenderer analysisRenderer = new AnalysisMarkdownRenderer(mermaidRenderer);
    private final EsqlParser esqlParser = new EsqlParser();
    private final EsqlInventoryRenderer esqlInventoryRenderer = new EsqlInventoryRenderer();

    @Option(names = "--input", converter = PathConverter.class, defaultValue = "input",
            description = "Файл .msgflow/.subflow/.esql или каталог с ними (по умолчанию ./input).")
    Path input;

    @Option(names = "--output", converter = PathConverter.class, defaultValue = "output",
            description = "Каталог для схемы и отчёта (создаётся при отсутствии; по умолчанию ./output).")
    Path output;

    @Option(names = "--orientation", defaultValue = "LR",
            description = "Ориентация Mermaid-диаграммы: LR (к голове вправо) или TB (сверху вниз).")
    String orientation;

    @Option(names = "--format", defaultValue = "all",
            description = "Формат вывода: mermaid (.mmd), adoc (.adoc), md (отчёт со встроенной схемой) "
                    + "или all (по умолчанию — все три).")
    String format;

    @Option(names = "--fail-fast", description = "Остановить пакетную обработку на первой ошибке разбора.")
    boolean failFast;

    @Option(names = "--inventory", description = "Карта очередей/топиков и подпотоков без чтения ESQL: Markdown, CSV и JSON.")
    boolean inventory;

    @Option(names = "--strict", description = "Код 2, если проект не готов к профилю пилота, включая неподдерживаемые конструкции.")
    boolean strict;

    static final class PathConverter implements ITypeConverter<Path> {
        @Override
        public Path convert(String value) {
            return Path.of(value);
        }
    }

    @Override
    public Integer call() throws IOException {
        Orientation orient;
        try {
            orient = Orientation.valueOf(orientation.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            System.err.println("Ошибка аргументов: неизвестная ориентация \"" + orientation
                    + "\" (допустимо LR или TB)");
            return 1;
        }

        List<String> formats;
        String fmt = format.toLowerCase(Locale.ROOT);
        switch (fmt) {
            case "mermaid", ".mmd" -> formats = List.of(".mmd");
            case "adoc", ".adoc" -> formats = List.of(".adoc");
            case "md", ".md" -> formats = List.of(".md");
            case "all" -> formats = List.of(".mmd", ".adoc", ".md");
            default -> {
                System.err.println("Ошибка аргументов: неизвестный формат \"" + format
                        + "\" (допустимо mermaid, adoc, md или all)");
                return 1;
            }
        }

        if (!Files.exists(input)) {
            System.err.println("Ошибка аргументов: вход не найден: " + input);
            return 1;
        }

        List<Path> files = collectFlowFiles();
        if (files == null) {
            return 1;
        }
        List<Path> esqlFiles = collectEsqlFiles();
        if (inventory) {
            return new InventoryAnalysis().run(input, output, files, esqlFiles, orient, failFast, strict);
        }
        if (files.isEmpty() && esqlFiles.isEmpty()) {
            System.err.println("В \"" + input + "\" не найдено файлов .msgflow/.subflow/.esql");
            return 3;
        }

        ArtifactOutput artifactOutput = new ArtifactOutput(output, "analyze");
        Path base = Files.isDirectory(input) ? input : input.getParent() == null ? Path.of(".") : input.getParent();

        Map<String, Set<String>> usedByExt = new HashMap<>();
        usedByExt.put(".md", new HashSet<>(Set.of("project-validation.md", "esql-inventory.md")));
        int rendered = 0;
        int artifacts = 0;
        int failures = 0;
        int nodes = 0;
        int edges = 0;
        List<EsqlInventoryRenderer.EsqlFileInfo> esqlInfos = new ArrayList<>();
        Map<Path, MsgFlow> parsedFlows = new LinkedHashMap<>();

        for (Path file : files) {
            try (InputStream in = Files.newInputStream(file)) {
                MsgFlow flow = parser.parse(in);
                ArtifactOutput.fileStem(diagramName(flow.name(), stemOf(file)));
                parsedFlows.put(file, flow);
                rendered++;
                nodes += flow.nodes().size();
                edges += flow.connections().size();
            } catch (IllegalArgumentException e) {
                System.err.println(base.relativize(file) + ": " + e.getMessage());
                failures++;
                if (failFast) {
                    break;
                }
            } catch (IOException e) {
                System.err.println(file + ": не удалось прочитать файл: " + e.getMessage());
                failures++;
                if (failFast) {
                    break;
                }
            }
        }
        EsqlInventoryRenderer.RenderResult esqlResult = null;
        int esqlFailures = 0;
        for (Path file : esqlFiles) {
            if (failFast && failures > 0) break;
            try {
                List<EsqlModule> modules = esqlParser.parseAll(Files.readString(file, StandardCharsets.UTF_8));
                esqlInfos.add(new EsqlInventoryRenderer.EsqlFileInfo(
                        base.relativize(file).toString(), modules, null));
            } catch (IllegalArgumentException | IOException e) {
                esqlInfos.add(new EsqlInventoryRenderer.EsqlFileInfo(
                        base.relativize(file).toString(), List.of(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                esqlFailures++;
                if (failFast) break;
            }
        }
        if (!esqlInfos.isEmpty()) {
            esqlResult = esqlInventoryRenderer.render(esqlInfos);
            artifactOutput.stage("esql-inventory.md", esqlResult.markdown());
        }

        MappingProject mappings = MappingProject.load(input);
        parsedFlows.replaceAll((file, flow) -> mappings.bind(flow));
        var modules = esqlInfos.stream().flatMap(info -> info.modules().stream()).toList();
        var diagnoses = ProjectValidation.inspect(parsedFlows, modules);
        boolean ready = failures + esqlFailures == 0 && mappings.errors().isEmpty()
                && diagnoses.values().stream().flatMap(List::stream).allMatch(d -> d.status() == UnitStatus.CONVERTED);
        var projectReport = new StringBuilder("# Проверка проекта\n\nГотовность к профилю пилота: **")
                .append(ready ? "да" : "нет").append("**.\n\n");
        for (var source : parsedFlows.entrySet()) {
            Path file = source.getKey();
            MsgFlow flow = source.getValue();
            String name = ArtifactOutput.fileStem(diagramName(flow.name(), stemOf(file)));
            String relative = base.relativize(file).toString();
            for (String extension : formats) {
                String fileName = uniqueName(name, extension, usedByExt.computeIfAbsent(extension, k -> new HashSet<>()));
                String content = switch (extension) {
                    case ".adoc" -> adocRenderer.render(flow, relative);
                    case ".md" -> analysisRenderer.render(flow, orient, relative, diagnoses.get(file));
                    default -> mermaidRenderer.render(flow, orient);
                };
                artifactOutput.stage(fileName, content);
                artifacts++;
            }
            projectReport.append("## ").append(relative).append("\n\n");
            var issues = diagnoses.get(file).stream().filter(d -> d.status() != UnitStatus.CONVERTED).toList();
            if (issues.isEmpty()) projectReport.append("Проверки пройдены.\n\n");
            issues.forEach(d -> projectReport.append("- `").append(d.nodeId()).append("`: ").append(d.note()).append('\n'));
        }
        for (var module : modules) {
            try {
                var bean = new ru.petrovov.iib_camel.converter.esql.EsqlToJavaGenerator().generate(module);
                if (!bean.complete()) {
                    ready = false;
                    projectReport.append("\nESQL `").append(module.name()).append("`: ").append(String.join("; ", bean.issues())).append('\n');
                }
            } catch (RuntimeException e) {
                ready = false;
                projectReport.append("\nESQL `").append(module.name()).append("`: ").append(e.getMessage()).append('\n');
            }
        }
        projectReport.append(mappings.markdown());
        if (failures + esqlFailures > 0) projectReport.append("\nОшибок разбора: ").append(failures + esqlFailures).append(". См. stderr и ESQL-инвентарь.\n");
        artifactOutput.stage("project-validation.md", ready ? projectReport.toString()
                : projectReport.toString().replace("пилота: **да**", "пилота: **нет**"));

        try {
            artifactOutput.publish();
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Вывод не опубликован: " + e.getMessage());
            return 3;
        }

        System.out.print("Инвентаризация: потоков " + rendered
                + " из " + files.size()
                + ", артефактов " + artifacts + (fmt.equals("all") ? " (3 формата)" : "")
                + ", узлов " + nodes
                + ", связей " + edges);
        if (esqlResult != null) {
            System.out.print("; ESQL: файлов " + esqlFiles.size()
                    + ", модулей " + esqlResult.total()
                    + " (переводимо полностью " + esqlResult.full()
                    + ", частично " + esqlResult.partial()
                    + ", вне подмножества " + esqlResult.outliers()
                    + "), esql-inventory.md");
        }
        System.out.println(", ошибок " + (failures + esqlFailures));
        return (failures + esqlFailures) == 0 ? strict && !ready ? 2 : 0
                : (rendered > 0 || esqlResult != null ? 2 : 3);
    }

    private List<Path> collectFlowFiles() throws IOException {
        if (Files.isRegularFile(input)) {
            if (isEsqlFile(input)) return List.of();
            if (!isFlowFile(input)) {
                System.err.println("Ошибка аргументов: ожидался файл .msgflow/.subflow, получен: " + input);
                return null;
            }
            return List.of(input);
        }
        try (Stream<Path> walk = Files.walk(input)) {
            return walk.filter(Files::isRegularFile)
                    .filter(AnalyzeCommand::isFlowFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private List<Path> collectEsqlFiles() throws IOException {
        if (Files.isRegularFile(input)) {
            return isEsqlFile(input) ? List.of(input) : List.of();
        }
        try (Stream<Path> walk = Files.walk(input)) {
            return walk.filter(Files::isRegularFile)
                    .filter(AnalyzeCommand::isEsqlFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static boolean isFlowFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".msgflow") || name.endsWith(".subflow");
    }

    private static boolean isEsqlFile(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".esql");
    }

    static String diagramName(String flowName, String fileStem) {
        return DiagramNames.of(flowName, fileStem);
    }

    private String uniqueName(String base, String extension, Set<String> used) {
        String candidate = base + extension;
        if (used.add(candidate)) {
            return candidate;
        }
        int index = 2;
        while (!used.add(base + "-" + index + extension)) {
            index++;
        }
        System.err.println(base + ": имя артефакта уже использовано, файл будет назван "
                + base + "-" + index + extension);
        return base + "-" + index + extension;
    }

    private static String stemOf(Path file) {
        String name = file.getFileName() == null ? "flow" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }
}
