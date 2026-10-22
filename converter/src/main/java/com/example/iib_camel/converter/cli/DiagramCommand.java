package com.example.iib_camel.converter.cli;

import com.example.iib_camel.converter.diagram.AsciiDiagramRenderer;
import com.example.iib_camel.converter.diagram.AsciiDocDiagramRenderer;
import com.example.iib_camel.converter.diagram.DiagramNames;
import com.example.iib_camel.converter.diagram.MermaidDiagramRenderer;
import com.example.iib_camel.converter.diagram.NodeCategoryRegistry;
import com.example.iib_camel.converter.diagram.Orientation;
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
 * Команда построения диаграмм из потоков IIB
 * (docs/diagram-renderer-design.ru.md, разделы 4.4 и 5).
 *
 * Контракт:
 *
 *   iib-camel-converter diagram [--input <файл|каталог>] [--output <каталог>]
 *                               [--orientation LR|TB] [--format mermaid|ascii|adoc|all] [--fail-fast]
 *
 * Вход — файл .msgflow/.subflow либо каталог (обход рекурсивно); по умолчанию ./input
 * относительно текущего каталога. Выход — по файлу диаграммы в каталоге --output
 * (по умолчанию ./output, создаётся при отсутствии): .mmd (Mermaid, канонический для GitHub),
 * .ascii (ASCII-конспект, D-8) либо .adoc (AsciiDoc-документ, D-8). По умолчанию
 * --format all — все три формата сразу, чтобы выбрать наиболее подходящий.
 * Диагностика — stderr, сводка — stdout.
 *
 * Коды возврата: 0 — всё отрисовано; 1 — неверные аргументы; 2 — частичный успех
 * (часть файлов не разобрана, остальные отрисованы); 3 — ни один файл не отрисован.
 */
@Command(name = "diagram",
         description = "Построение диаграмм из потоков IIB (.msgflow, .subflow).")
public final class DiagramCommand implements Callable<Integer> {

    private final MsgFlowParser parser = new MsgFlowParser();
    private final MermaidDiagramRenderer renderer =
            new MermaidDiagramRenderer(NodeCategoryRegistry.withIibDefaults());
    private final AsciiDiagramRenderer asciiRenderer = new AsciiDiagramRenderer();
    private final AsciiDocDiagramRenderer adocRenderer = new AsciiDocDiagramRenderer();

    @Option(names = "--input", converter = PathConverter.class, defaultValue = "input",
            description = "Файл .msgflow/.subflow или каталог с ними (по умолчанию ./input).")
    Path input;

    @Option(names = "--output", converter = PathConverter.class, defaultValue = "output",
            description = "Каталог для диаграмм (создаётся при отсутствии; по умолчанию ./output).")
    Path output;

    @Option(names = "--orientation", defaultValue = "LR",
            description = "Ориентация Mermaid-диаграммы: LR (к голове вправо) или TB (сверху вниз).")
    String orientation;

    @Option(names = "--format", defaultValue = "all",
            description = "Формат вывода: mermaid (.mmd), ascii (.ascii), adoc (.adoc) "
                    + "или all (по умолчанию all — сгенерировать все три).")
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
            case "ascii", ".ascii" -> formats = List.of(".ascii");
            case "adoc", ".adoc" -> formats = List.of(".adoc");
            case "all" -> formats = List.of(".mmd", ".ascii", ".adoc");
            default -> {
                System.err.println("Ошибка аргументов: неизвестный формат \"" + format
                        + "\" (допустимо mermaid, ascii, adoc или all)");
                return 1;
            }
        }

        if (!Files.exists(input)) {
            System.err.println("Ошибка аргументов: вход не найден: " + input);
            return 1;
        }

        List<Path> files = collectFiles();
        if (files == null) {
            return 1; // одиночный файл неподдерживаемого расширения
        }
        if (files.isEmpty()) {
            System.err.println("В \"" + input + "\" не найдено файлов .msgflow/.subflow");
            return 3;
        }

        Files.createDirectories(output);

        // База для сообщений: путь файла относительно корня входа (FR-1.3: файл+строка в диагностике)
        Path base = Files.isDirectory(input) ? input : input.getParent() == null ? Path.of(".") : input.getParent();

        Map<String, Set<String>> usedByExt = new HashMap<>();
        int rendered = 0;
        int diagrams = 0;
        int failures = 0;
        int nodes = 0;
        int edges = 0;

        for (Path file : files) {
            try (InputStream in = Files.newInputStream(file)) {
                MsgFlow flow = parser.parse(in);
                String name = diagramName(flow.name(), stemOf(file));
                for (String extension : formats) {
                    String fileName = uniqueName(name, extension,
                            usedByExt.computeIfAbsent(extension, k -> new HashSet<>()));
                    String content = switch (extension) {
                        case ".ascii" -> asciiRenderer.render(flow);
                        case ".adoc" -> adocRenderer.render(flow, base.relativize(file).toString());
                        default -> renderer.render(flow, orient);
                    };
                    Files.writeString(output.resolve(fileName), content, StandardCharsets.UTF_8);
                    diagrams++;
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

        System.out.println("Диаграммы: потоков " + rendered
                + " из " + files.size()
                + ", диаграмм " + diagrams + (fmt.equals("all") ? " (3 формата)" : "")
                + ", узлов " + nodes
                + ", связей " + edges
                + ", ошибок " + failures);
        return failures == 0 ? 0 : (rendered > 0 ? 2 : 3);
    }

    /** Сбор входных файлов: одиночный файл либо рекурсивный обход каталога (детерминированный порядок). */
    private List<Path> collectFiles() throws IOException {
        if (Files.isRegularFile(input)) {
            if (!isFlowFile(input)) {
                System.err.println("Ошибка аргументов: ожидался файл .msgflow/.subflow, получен: " + input);
                return null;
            }
            return List.of(input);
        }
        try (Stream<Path> walk = Files.walk(input)) {
            return walk.filter(Files::isRegularFile)
                    .filter(DiagramCommand::isFlowFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static boolean isFlowFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".msgflow") || name.endsWith(".subflow");
    }

    /** Имя файла диаграммы: политика DiagramNames (имя IR без расширения, fallback — stem файла). */
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
        System.err.println(base + ": имя диаграммы уже использовано, файл будет назван "
                + base + "-" + index + extension);
        return base + "-" + index + extension;
    }

    private static String stemOf(Path file) {
        String name = file.getFileName() == null ? "flow" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }
}
