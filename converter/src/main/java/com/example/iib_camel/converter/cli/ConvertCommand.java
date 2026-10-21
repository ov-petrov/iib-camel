package com.example.iib_camel.converter.cli;

import com.example.iib_camel.converter.camel.RouteGenerator;
import com.example.iib_camel.converter.msgflow.FlowNode;
import com.example.iib_camel.converter.msgflow.MsgFlow;
import com.example.iib_camel.converter.msgflow.MsgFlowParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Команда конвертации потоков IIB в маршруты Apache Camel (FR-2.1–FR-2.7, FR-5.1, FR-7.1).
 *
 * Контракт:
 *   iib-camel-converter convert --input &lt;файл|.msgflow-каталог&gt; [--output каталог]
 *
 * Конвейер (FR-2.1, Must): парсер → генератор маршрутов → вывод + отчёт.
 * Выход: для каждого .msgflow — файл &lt;Имя&gt;-route.xml; единый migration-report.md.
 * Коды возврата — как у diagram: 0 — успех, 2 — частичный успех, 3 — ни один файл.
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
    private int convertedFiles;
    private int failedFiles;

    @Override
    public Integer call() {
        List<Path> flows = collectMsgFlows();
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

        var report = new StringBuilder("# Отчёт о миграции\n\n");
        report.append("Конвертер iib-camel (one-shot CLI), FR-5.1. Источник: `")
                .append(input).append("`.\n\n## Потоки\n\n");
        report.append("| Поток | Результат | Узлы | Не поддерживается |\n|---|---|---|---|\n");

        for (Path file : flows) {
            try {
                MsgFlow flow = parse(file);
                String routeId = flow.name().replaceAll("\\.msgflow$", "");
                Path routeFile = output.resolve(routeId + "-route.xml");
                String xml = new RouteGenerator(flow).generate();
                Files.writeString(routeFile, xml, StandardCharsets.UTF_8);
                convertedFiles++;
                List<FlowNode> unsupported = flow.nodes().stream()
                        .filter(n -> !RouteGenerator.SUPPORTED_BASE_KINDS.contains(n.kind()))
                        .toList();
                report.append("| ").append(flow.name())
                        .append("| [`").append(routeFile.getFileName()).append("`](")
                        .append(routeFile.getFileName()).append(") | ")
                        .append(flow.nodes().size()).append(" | ")
                        .append(unsupported.size()).append(" |\n");
                for (FlowNode node : unsupported) {
                    report.append("\n> ⚠️ **Не поддерживается** (FR-2.7): ").append(flow.name())
                            .append(" — узел `").append(node.id()).append("` (тип ")
                            .append(node.kind()).append(") — требуется ручная миграция.\n");
                }
            } catch (Exception e) {
                failedFiles++;
                errors.add(file + ": " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            report.append("\n## Ошибки\n\n");
            errors.forEach(e -> report.append("- `").append(e).append("`\n"));
        }
        report.append("\n## Сводка\n\n- Файлов: ").append(flows.size())
                .append(" (конвертировано ").append(convertedFiles)
                .append(", с ошибками ").append(failedFiles).append(")\n");
        try {
            Files.writeString(output.resolve("migration-report.md"), report.toString(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            errors.add(output.resolve("migration-report.md") + ": " + e.getMessage());
        }

        System.out.println("Конвертация: файлов " + flows.size()
                + " из " + input + " → " + output
                + "; конвертировано " + convertedFiles
                + ", ошибок " + errors.size());
        if (errors.size() == flows.size()) {
            return 3;
        }
        return errors.isEmpty() ? 0 : 2;
    }

    private List<Path> collectMsgFlows() {
        try (Stream<Path> walk = Files.walk(input)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".msgflow"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private MsgFlow parse(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return new MsgFlowParser().parse(in);
        }
    }
}
