package com.example.iib_camel.converter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт CLI-команды diagram (дизайн-документ, раздел 5):
 * коды возврата 0/1/2/3, выходной файл .mmd, коллизии имён.
 */
class DiagramCommandTest {

    private static final String SAMPLE_MSGFLOW = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ecore:EPackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
                xmlns:ComIbmMQInput.msgnode="ComIbmMQInput.msgnode" xmlns:ComIbmMQOutput.msgnode="ComIbmMQOutput.msgnode"
                xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore" nsURI="Sample.msgflow" nsPrefix="Sample.msgflow">
              <eClassifiers xmi:type="eflow:FCMComposite" name="FCMComposite_1">
                <composition>
                  <nodes xmi:type="ComIbmMQInput.msgnode:FCMComposite_1" xmi:id="In" queueName="SAMPLE.IN">
                    <translation xmi:type="utility:ConstantString" string="Sample Input"/>
                  </nodes>
                  <nodes xmi:type="ComIbmMQOutput.msgnode:FCMComposite_1" xmi:id="Out" queueName="SAMPLE.OUT">
                    <translation xmi:type="utility:ConstantString" string="Sample Output"/>
                  </nodes>
                  <connections xmi:type="eflow:FCMConnection" targetNode="Out" sourceNode="In"
                               sourceTerminalName="OutTerminal.out" targetTerminalName="InTerminal.in"/>
                </composition>
              </eClassifiers>
            </ecore:EPackage>
            """;

    private static final String BROKEN_MSGFLOW = "<ecore:EPackage xmlns:ecore=\"http://www.eclipse.org/emf/2002/Ecore\"";

    private final DiagramCommand command = new DiagramCommand();

    private int run(String... args) {
        return new CommandLine(command).execute(args);
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void rendersAllFilesInDirectory(@TempDir Path temp) throws Exception {
        Path input = temp.resolve("in");
        Files.createDirectories(input);
        Files.writeString(input.resolve("Sample.msgflow"), SAMPLE_MSGFLOW);
        Path output = temp.resolve("out");

        int code = run("--input", input.toString(), "--output", output.toString());

        assertEquals(0, code);
        Path diagram = output.resolve("Sample.mmd");
        String content = Files.readString(diagram);
        assertTrue(content.contains("flowchart LR"));
        assertTrue(content.contains("Sample Input<br/>MQInput<br/>SAMPLE.IN"));
        assertTrue(content.contains("N1 --> N2"));
    }

    @Test
    void sourceNameIsTakenFromNsUriWithoutExtension(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("AnySource.msgflow");
        Files.writeString(file, SAMPLE_MSGFLOW);
        Path output = temp.resolve("out");

        assertEquals(0, run("--input", file.toString(), "--output", output.toString()));

        assertTrue(Files.exists(output.resolve("Sample.mmd")));
    }

    @Test
    void nameCollisionsAreResolvedWithSuffix(@TempDir Path temp) throws Exception {
        Path input = temp.resolve("in");
        Files.createDirectories(input);
        Files.writeString(input.resolve("First.msgflow"), SAMPLE_MSGFLOW.replace("Sample.msgflow\" nsPrefix", "Shared.msgflow\" nsPrefix"));
        Files.writeString(input.resolve("Second.msgflow"), SAMPLE_MSGFLOW.replace("Sample.msgflow\" nsPrefix", "Shared.msgflow\" nsPrefix"));
        Path output = temp.resolve("out");

        assertEquals(0, run("--input", input.toString(), "--output", output.toString()));

        assertTrue(Files.exists(output.resolve("Shared.mmd")));
        assertTrue(Files.exists(output.resolve("Shared-2.mmd")));
    }

    @Test
    void partialFailureReturnsExitCode2(@TempDir Path temp) throws Exception {
        Path input = temp.resolve("in");
        Files.createDirectories(input);
        Files.writeString(input.resolve("Good.msgflow"), SAMPLE_MSGFLOW);
        Files.writeString(input.resolve("Broken.msgflow"), BROKEN_MSGFLOW);
        Path output = temp.resolve("out");

        int code = run("--input", input.toString(), "--output", output.toString());

        assertEquals(2, code);
        assertTrue(Files.exists(output.resolve("Sample.mmd")));
    }

    @Test
    void invalidOrientationReturnsExitCode1(@TempDir Path temp) throws Exception {
        Path input = temp.resolve("in");
        Files.createDirectories(input);
        Files.writeString(input.resolve("Sample.msgflow"), SAMPLE_MSGFLOW);

        assertEquals(1, run("--input", input.toString(), "--orientation", "DIAGONAL"));
    }

    @Test
    void missingInputReturnsExitCode1(@TempDir Path temp) {
        assertEquals(1, run("--input", temp.resolve("no-such-dir").toString()));
    }

    @Test
    void defaultFormatRendersAllThreeFormats(@TempDir Path temp) throws Exception {
        Path input = temp.resolve("in");
        Files.createDirectories(input);
        Files.writeString(input.resolve("Sample.msgflow"), SAMPLE_MSGFLOW);
        Path output = temp.resolve("out");

        assertEquals(0, run("--input", input.toString(), "--output", output.toString()));

        assertTrue(Files.exists(output.resolve("Sample.mmd")));
        assertTrue(Files.exists(output.resolve("Sample.ascii")));
        assertTrue(Files.exists(output.resolve("Sample.adoc")));
    }

    @Test
    void noArgumentsResolveToCwdInputAndOutput() throws Exception {
        Path input = Path.of("input");
        Path output = Path.of("output");
        Files.createDirectories(input);
        Files.writeString(input.resolve("Sample.msgflow"), SAMPLE_MSGFLOW);
        try {
            assertEquals(0, run());
            assertTrue(Files.exists(output.resolve("Sample.mmd")), "дефолт --output: ./output");
            assertTrue(Files.exists(output.resolve("Sample.ascii")));
            assertTrue(Files.exists(output.resolve("Sample.adoc")));
        } finally {
            deleteRecursively(input);
            deleteRecursively(output);
        }
    }

    @Test
    void directoryWithoutFlowsReturnsExitCode3(@TempDir Path temp) throws Exception {
        Path empty = temp.resolve("empty");
        Files.createDirectories(empty);

        assertEquals(3, run("--input", empty.toString()));
    }

    @Test
    void unsupportedSingleFileReturnsExitCode1(@TempDir Path temp) throws Exception {
        Path notes = temp.resolve("notes.txt");
        Files.writeString(notes, "text");

        assertEquals(1, run("--input", notes.toString()));
    }

    @Test
    void asciiFormatWritesAsciiFile(@TempDir Path temp) throws Exception {
        Path input = temp.resolve("in");
        Files.createDirectories(input);
        Files.writeString(input.resolve("Sample.msgflow"), SAMPLE_MSGFLOW);
        Path output = temp.resolve("out");

        assertEquals(0, run("--input", input.toString(), "--output", output.toString(),
                "--format", "ascii"));

        String content = Files.readString(output.resolve("Sample.ascii"));
        assertTrue(content.contains("MQInput «Sample Input» [SAMPLE.IN]"));
        assertTrue(content.contains("└─ out → MQOutput «Sample Output» [SAMPLE.OUT]"));
    }

    @Test
    void adocFormatWritesAsciiDocDocument(@TempDir Path temp) throws Exception {
        Path input = temp.resolve("in");
        Files.createDirectories(input);
        Files.writeString(input.resolve("Sample.msgflow"), SAMPLE_MSGFLOW);
        Path output = temp.resolve("out");

        assertEquals(0, run("--input", input.toString(), "--output", output.toString(),
                "--format", "adoc"));

        String content = Files.readString(output.resolve("Sample.adoc"));
        assertTrue(content.contains("= Sample"));
        assertTrue(content.contains("MQInput «Sample Input» [SAMPLE.IN]"));
        assertTrue(content.contains("| In | MQInput | Sample Input | queueName = SAMPLE.IN"));
    }

    @Test
    void invalidFormatReturnsExitCode1(@TempDir Path temp) throws Exception {
        Path input = temp.resolve("in");
        Files.createDirectories(input);
        Files.writeString(input.resolve("Sample.msgflow"), SAMPLE_MSGFLOW);

        assertEquals(1, run("--input", input.toString(), "--format", "svg"));
    }

    @Test
    void diagramNameStripsExtensionsAndFallsBackToStem() {
        assertEquals("Adapter", DiagramCommand.diagramName("Adapter.msgflow", "Adapter"));
        assertEquals("Check", DiagramCommand.diagramName("Check.subflow", "Check"));
        assertEquals("Stem", DiagramCommand.diagramName("   ", "Stem"));
        assertEquals("Composite", DiagramCommand.diagramName("Composite", "ignored"));
        assertFalse(DiagramCommand.diagramName("Adapter.msgflow", "x").contains("."));
    }
}
