package com.example.iib_camel.converter.cli;

import com.example.iib_camel.converter.diagram.AnalysisMarkdownRenderer;
import com.example.iib_camel.converter.diagram.AsciiDocDiagramRenderer;
import com.example.iib_camel.converter.diagram.DiagramNames;
import com.example.iib_camel.converter.diagram.MermaidDiagramRenderer;
import com.example.iib_camel.converter.diagram.NodeCategoryRegistry;
import com.example.iib_camel.converter.diagram.Orientation;
import com.example.iib_camel.converter.esql.EsqlInventoryRenderer;
import com.example.iib_camel.converter.esql.EsqlModule;
import com.example.iib_camel.converter.esql.EsqlParser;
import com.example.iib_camel.converter.msgflow.MsgFlow;
import com.example.iib_camel.converter.msgflow.MsgFlowParser;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Команда инвентаризации системы IIB **до миграции** (docs/diagram-renderer-design.ru.md).
 *
 * Контракт:
 *
 *   iib-camel-converter analyze [--input &lt;файл|каталог&gt;] [--output &lt;каталог&gt;]
 *                               [--orientation LR|TB] [--format mermaid|adoc|md|all] [--fail-fast]
 *
 * Результат — «отчёт + визуализация текущей системы»: .mmd (Mermaid, канонический для
 * GitHub), .adoc (AsciiDoc-документ) и .md (инвентаризационный отчёт со встроенной
 * ```mermaid-схемой, таблицами узлов/связей и вердиктом о покрытии: предусмотрена ли
 * обработка всех узлов IIB). ASCII-схема («дерево-конспект») удалена как дублирующая
 * Mermaid. Полный отчёт о конвертации — команда convert (FR-5.1).
 *
 * Дополнительно по .esql-файлам (в той же директории, что и схемы, или же по одиночному
 * .esql) строится системный инвентарь ESQL «как есть» — `esql-inventory.md`: модули,
 * функции, операторы и оценка переводимости по подмножеству FR-3.2. Артефакты конвертации
 * (бины, модели) на этой стадии не создаются — их показывает отчёт convert.
 *
 * Вход — файл .msgflow/.subflow/.esql либо каталог (обход рекурсивно); по умолчанию ./input.
 * Выход — каталог --output (по умолчанию ./output, создаётся при отсутствии).
 * Диагностика — stderr, сводка — stdout.
 *
 * Коды возврата: 0 — всё отрисовано; 1 — неверные аргументы; 2 — частичный успех
 * (часть файлов не разобрана, остальные отрисованы); 3 — ни один файл не отрисован.
 */
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

    /** Явный конвертер путей — не полагаемся на встроенные правила picocli. */
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
            return 1; // одиночный файл неподдерживаемого расширения
        }
        List<Path> esqlFiles = collectEsqlFiles();
        if (files.isEmpty() && esqlFiles.isEmpty()) {
            System.err.println("В \"" + input + "\" не найдено файлов .msgflow/.subflow/.esql");
            return 3;
        }

        Files.createDirectories(output);

        // База для сообщений: путь файла относительно корня входа (FR-1.3: файл+строка в диагностике)
        Path base = Files.isDirectory(input) ? input : input.getParent() == null ? Path.of(".") : input.getParent();

        Map<String, Set<String>> usedByExt = new HashMap<>();
        int rendered = 0;
        int artifacts = 0;
        int failures = 0;
        int nodes = 0;
        int edges = 0;
        List<EsqlInventoryRenderer.EsqlFileInfo> esqlInfos = new ArrayList<>();

        for (Path file : files) {
            try (InputStream in = Files.newInputStream(file)) {
                MsgFlow flow = parser.parse(in);
                String name = diagramName(flow.name(), stemOf(file));
                String relative = base.relativize(file).toString();
                for (String extension : formats) {
                    String fileName = uniqueName(name, extension,
                            usedByExt.computeIfAbsent(extension, k -> new HashSet<>()));
                    String content = switch (extension) {
                        case ".adoc" -> adocRenderer.render(flow, relative);
                        case ".md" -> analysisRenderer.render(flow, orient, relative);
                        default -> mermaidRenderer.render(flow, orient);
                    };
                    Files.writeString(output.resolve(fileName), content, StandardCharsets.UTF_8);
                    artifacts++;
                }
                rendered++;
                nodes += flow.nodes().size();
                edges += flow.connections().size();
            } catch (IllegalArgumentException e) {
                // ошибки разбора оборачиваются парсером IR в IllegalArgumentException с описанием
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

        // Системный инвентарь ESQL «как есть» (отдельные артефакты конвертации не создаются):
        // ошибки разбора ESQL учитываются в общем счётчике failures (политика кодов не меняется).
        EsqlInventoryRenderer.RenderResult esqlResult = null;
        int esqlFailures = 0;
        for (Path file : esqlFiles) {
            try {
                List<EsqlModule> modules = esqlParser.parseAll(Files.readString(file, StandardCharsets.UTF_8));
                esqlInfos.add(new EsqlInventoryRenderer.EsqlFileInfo(
                        base.relativize(file).toString(), modules, null));
            } catch (IllegalArgumentException | IOException e) {
                // честная строка ошибки в инвентаре вместо молчаливого пропуска файла
                esqlInfos.add(new EsqlInventoryRenderer.EsqlFileInfo(
                        base.relativize(file).toString(), List.of(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                esqlFailures++;
            }
        }
        if (!esqlInfos.isEmpty()) {
            esqlResult = esqlInventoryRenderer.render(esqlInfos);
            Files.writeString(output.resolve("esql-inventory.md"),
                    esqlResult.markdown(), StandardCharsets.UTF_8);
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
        return (failures + esqlFailures) == 0 ? 0
                : (rendered > 0 || esqlResult != null ? 2 : 3);
    }

    /** Сбор файлов схем: одиночный файл либо рекурсивный обход каталога (детерминированный порядок). */
    private List<Path> collectFlowFiles() throws IOException {
        if (Files.isRegularFile(input)) {
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

    /** Сбор ESQL-файлов: одиночный .esql либо обход каталога; список может быть пустым. */
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

    /** Имя файла схемы: политика DiagramNames (имя IR без расширения, fallback — stem файла). */
    static String diagramName(String flowName, String fileStem) {
        return DiagramNames.of(flowName, fileStem);
    }

    /** Разрешение коллизий имён в плоском каталоге вывода: Adapter.mmd, Adapter-2.mmd, … */
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
