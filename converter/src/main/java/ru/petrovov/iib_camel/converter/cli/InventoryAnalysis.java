package ru.petrovov.iib_camel.converter.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import ru.petrovov.iib_camel.converter.analysis.ProjectAnalyzer;
import ru.petrovov.iib_camel.converter.analysis.model.FlowDocument;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.Diagnostic;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.Severity;
import ru.petrovov.iib_camel.converter.analysis.model.SourceRef;
import ru.petrovov.iib_camel.converter.analysis.report.InventoryRenderer;
import ru.petrovov.iib_camel.converter.diagram.Orientation;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlowParser;

final class InventoryAnalysis {
    int run(Path input, Path output, List<Path> files, List<Path> esqlFiles, Orientation orientation,
              boolean failFast, boolean strict) throws IOException {
        if (files.isEmpty()) {
            System.err.println("Для --inventory нужны .msgflow/.subflow; содержимое ESQL не анализируется");
            return 3;
        }
        Path base = Files.isDirectory(input) ? input : input.getParent() == null ? Path.of(".") : input.getParent();
        List<FlowDocument> documents = new ArrayList<>();
        List<Diagnostic> failures = new ArrayList<>();
        MsgFlowParser parser = new MsgFlowParser();
        for (Path file : files) {
            String relative = relative(base, file);
            try (InputStream stream = Files.newInputStream(file)) {
                documents.add(parser.parseDocument(stream, relative));
            } catch (IllegalArgumentException | IOException exception) {
                failures.add(new Diagnostic("SOURCE_PARSE_ERROR", Severity.ERROR, relative, "",
                        exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(),
                        SourceRef.file(relative)));
                System.err.println(relative + ": " + exception.getMessage());
                if (failFast) {
                    break;
                }
            }
        }
        List<String> skipped = esqlFiles.stream().map(file -> relative(base, file)).toList();
        var analysis = new ProjectAnalyzer().analyze(documents, failures, skipped);
        ArtifactOutput artifacts = new ArtifactOutput(output, "inventory");
        try {
            new InventoryRenderer().render(analysis, orientation).forEach(artifacts::stage);
            artifacts.publish();
        } catch (IOException | IllegalArgumentException exception) {
            System.err.println("Инвентарь не опубликован: " + exception.getMessage());
            return 3;
        }
        long definitions = documents.stream().filter(FlowDocument::subflow).count();
        long warnings = analysis.diagnostics().stream().filter(issue -> issue.severity() == Severity.WARNING).count();
        long errors = analysis.diagnostics().stream().filter(issue -> issue.severity() == Severity.ERROR).count();
        System.out.println("Инвентарь: потоков " + (documents.size() - definitions) + ", определений подпотоков "
                + definitions + ", использований очередей/топиков " + analysis.endpoints().size()
                + ", связей потоков " + analysis.links().size() + ", предупреждений " + warnings
                + ", ошибок " + errors + "; ESQL пропущено " + skipped.size() + "; system-overview.md");
        return documents.isEmpty() ? 3 : errors > 0 || strict && warnings > 0 ? 2 : 0;
    }

    private static String relative(Path base, Path file) {
        return base.relativize(file).toString().replace('\\', '/');
    }
}
