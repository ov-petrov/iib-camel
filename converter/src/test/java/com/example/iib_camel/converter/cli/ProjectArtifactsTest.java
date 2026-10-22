package com.example.iib_camel.converter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectArtifactsTest {

    @TempDir
    Path temp;

    @Test
    void duplicateFlowNsUrisFailAsAProjectAndWriteNoRoutes() throws Exception {
        Path input = Files.createDirectories(temp.resolve("duplicate-flows"));
        Files.writeString(input.resolve("first.msgflow"), mqFlow("Shared.msgflow", "IN1", "OUT1"));
        Files.writeString(input.resolve("second.msgflow"), mqFlow("Shared.msgflow", "IN2", "OUT2"));
        Path output = temp.resolve("out-duplicate-flows");

        assertEquals(3, convert(input, output));
        assertTrue(Files.readString(output.resolve("migration-report.md")).contains("конфликт идентификатора"));
        assertFalse(Files.exists(output.resolve("Shared-route.xml")));
        assertEquals(List.of(), routeFiles(output));
    }

    @Test
    void sharedSubflowConsumerIsEmittedOnlyOnceAcrossParentRoutes() throws Exception {
        Path input = Files.createDirectories(temp.resolve("shared-subflow"));
        Files.writeString(input.resolve("First.msgflow"), parentFlow("First.msgflow", "Shared"));
        Files.writeString(input.resolve("Second.msgflow"), parentFlow("Second.msgflow", "Shared"));
        Files.writeString(input.resolve("Shared.subflow"), subflow("Shared.subflow"));
        Path output = temp.resolve("out-shared-subflow");

        assertEquals(0, convert(input, output));
        String allRoutes = readRoutes(output);
        assertEquals(1, occurrences(allRoutes, "<from uri=\"direct:Shared\"/>"));
        assertTrue(allRoutes.contains("<to uri=\"direct:Shared\"/>"));
    }

    @Test
    void qualifiedSubflowsWithSameSourceFilenameResolveBySchemaName() throws Exception {
        Path input = Files.createDirectories(temp.resolve("qualified-subflows"));
        Path schemaA = Files.createDirectories(input.resolve("SchemaA"));
        Path schemaB = Files.createDirectories(input.resolve("SchemaB"));
        Files.writeString(input.resolve("UseA.msgflow"), parentFlow("UseA.msgflow", "SchemaA_Shared"));
        Files.writeString(input.resolve("UseB.msgflow"), parentFlow("UseB.msgflow", "SchemaB_Shared"));
        Files.writeString(schemaA.resolve("Shared.subflow"), subflow("SchemaA_Shared.subflow"));
        Files.writeString(schemaB.resolve("Shared.subflow"), subflow("SchemaB_Shared.subflow"));
        Path output = temp.resolve("out-qualified-subflows");

        assertEquals(0, convert(input, output));
        String useA = Files.readString(output.resolve("UseA-route.xml"));
        String useB = Files.readString(output.resolve("UseB-route.xml"));
        String allRoutes = readRoutes(output);
        assertTrue(useA.contains("direct:SchemaA_Shared"));
        assertFalse(useA.contains("direct:SchemaB_Shared"));
        assertTrue(useB.contains("direct:SchemaB_Shared"));
        assertFalse(useB.contains("direct:SchemaA_Shared"));
        assertTrue(allRoutes.contains("<route id=\"SchemaA_Shared\">"));
        assertTrue(allRoutes.contains("<route id=\"SchemaB_Shared\">"));
        assertFalse(allRoutes.contains("direct:Shared\""));
    }

    @Test
    void ambiguousShortSubflowNameCannotSelectAnArbitrarySource() throws Exception {
        Path input = Files.createDirectories(temp.resolve("ambiguous-alias"));
        Files.writeString(input.resolve("Main.msgflow"), parentFlow("Main.msgflow", "Shared"));
        Path a = Files.createDirectories(input.resolve("a"));
        Path b = Files.createDirectories(input.resolve("b"));
        Files.writeString(a.resolve("Shared.subflow"), subflow("SchemaA_Shared.subflow"));
        Files.writeString(b.resolve("Shared.subflow"), subflow("SchemaB_Shared.subflow"));
        Path output = temp.resolve("out-ambiguous");
        assertEquals(3, convert(input, output));
        assertTrue(Files.readString(output.resolve("migration-report.md")).contains("неоднозначная ссылка"));
        assertTrue(routeFiles(output).isEmpty());
    }

    @Test
    void sourceFilenameAliasCannotHideAnotherCanonicalSubflowName() throws Exception {
        Path input = Files.createDirectories(temp.resolve("canonical-alias"));
        Files.writeString(input.resolve("Main.msgflow"), parentFlow("Main.msgflow", "Shared"));
        Files.writeString(input.resolve("Shared.subflow"), subflow("Schema_Shared.subflow"));
        Files.writeString(input.resolve("Other.subflow"), subflow("Shared.subflow"));
        Path output = temp.resolve("out-canonical-alias");
        assertEquals(3, convert(input, output));
        assertTrue(Files.readString(output.resolve("migration-report.md")).contains("неоднозначная ссылка"));
        assertTrue(routeFiles(output).isEmpty());
    }

    @Test
    void sameEsqlModuleNameInDifferentSchemasFailsWithoutOverwritingBean() throws Exception {
        Path input = Files.createDirectories(temp.resolve("duplicate-esql"));
        Path schemaA = Files.createDirectories(input.resolve("a"));
        Path schemaB = Files.createDirectories(input.resolve("b"));
        Files.writeString(input.resolve("Main.msgflow"), mqFlow("Main.msgflow", "IN", "OUT"));
        Files.writeString(schemaA.resolve("SharedA.esql"), esqlModule("SchemaA", "SharedCompute", "Status"));
        Files.writeString(schemaB.resolve("SharedB.esql"), esqlModule("SchemaB", "SharedCompute", "Rating"));
        Path output = temp.resolve("out-duplicate-esql");

        assertEquals(3, convert(input, output));
        String report = Files.readString(output.resolve("migration-report.md"));
        assertTrue(report.contains("конфликт идентификатора"));
        assertFalse(Files.exists(output.resolve("beans/SharedCompute.java")));
    }

    @Test
    void compatibleModelsFromDifferentModulesAreAggregated() throws Exception {
        Path input = Files.createDirectories(temp.resolve("aggregate-models"));
        Files.writeString(input.resolve("Main.msgflow"), mqFlow("Main.msgflow", "IN", "OUT"));
        Files.writeString(input.resolve("Status.esql"), esqlModule("SchemaA", "StatusCompute", "Status"));
        Files.writeString(input.resolve("Rating.esql"), esqlModule("SchemaB", "RatingCompute", "Rating"));
        Path output = temp.resolve("out-aggregate-models");

        assertEquals(0, convert(input, output));
        String model = Files.readString(output.resolve("models/Order.java"));
        assertTrue(model.contains("private String Status;"));
        assertTrue(model.contains("private String Rating;"));
    }

    @Test
    void unsafeNsUriCannotWriteOutsideOutputDirectory() throws Exception {
        Path input = Files.createDirectories(temp.resolve("unsafe-name"));
        Files.writeString(input.resolve("escape.msgflow"), mqFlow("../escaped.msgflow", "IN", "OUT"));
        Path output = temp.resolve("out-unsafe-name");

        assertEquals(3, convert(input, output));
        String report = Files.readString(output.resolve("migration-report.md"));
        assertTrue(report.contains("небезопасное имя артефакта"));
        assertFalse(Files.exists(temp.resolve("escaped-route.xml")));
        assertEquals(List.of(), routeFiles(output));
    }

    @Test
    void analyzeAcceptsSingleEsqlFileAndWritesInventory() throws Exception {
        Path esql = temp.resolve("Only.esql");
        Files.writeString(esql, esqlModule("OnlySchema", "OnlyCompute", "Status"));
        Path output = temp.resolve("out-esql-inventory");

        assertEquals(0, new CommandLine(new AnalyzeCommand()).execute(
                "--input", esql.toString(), "--output", output.toString()));
        assertTrue(Files.exists(output.resolve("esql-inventory.md")));
        assertTrue(Files.readString(output.resolve("esql-inventory.md")).contains("OnlyCompute"));
    }

    private int convert(Path input, Path output) {
        return new CommandLine(new ConvertCommand()).execute(
                "--input", input.toString(), "--output", output.toString());
    }

    private String mqFlow(String nsUri, String inputQueue, String outputQueue) {
        return header(nsUri, "xmlns:mqin=\"ComIbmMQInput.msgnode\" xmlns:mqout=\"ComIbmMQOutput.msgnode\"")
                + """
                  <nodes xmi:type="mqin:FCMComposite_1" xmi:id="In" queueName="%s"/>
                  <nodes xmi:type="mqout:FCMComposite_1" xmi:id="Out" queueName="%s"/>
                  <connections sourceNode="In" targetNode="Out" sourceTerminalName="OutTerminal.out" targetTerminalName="InTerminal.in"/>
                </ecore:EPackage>
                """.formatted(inputQueue, outputQueue);
    }

    private String parentFlow(String nsUri, String target) {
        return header(nsUri, "xmlns:mqin=\"ComIbmMQInput.msgnode\" xmlns:mqout=\"ComIbmMQOutput.msgnode\" xmlns:sub=\""
                        + target + ".subflow\"") + """
                  <nodes xmi:type="mqin:FCMComposite_1" xmi:id="In" queueName="%s.IN"/>
                  <nodes xmi:type="sub:FCMComposite_1" xmi:id="Call"/>
                  <nodes xmi:type="mqout:FCMComposite_1" xmi:id="Out" queueName="%s.OUT"/>
                  <connections sourceNode="In" targetNode="Call" sourceTerminalName="OutTerminal.out" targetTerminalName="InTerminal.in"/>
                  <connections sourceNode="Call" targetNode="Out" sourceTerminalName="OutTerminal.out" targetTerminalName="InTerminal.in"/>
                </ecore:EPackage>
                """.formatted(nsUri.replaceFirst("\\.(msgflow|subflow)$", ""), nsUri.replaceFirst("\\.(msgflow|subflow)$", ""));
    }

    private String subflow(String nsUri) {
        return header(nsUri, "") + """
                  <nodes xmi:type="eflow:FCMSource" xmi:id="In"/>
                  <nodes xmi:type="eflow:FCMSink" xmi:id="Out"/>
                  <connections sourceNode="In" targetNode="Out" sourceTerminalName="OutTerminal.out" targetTerminalName="InTerminal.in"/>
                </ecore:EPackage>
                """;
    }

    private String header(String nsUri, String declarations) {
        return """
                <ecore:EPackage xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore"
                    xmlns:xmi="http://www.omg.org/XMI" xmlns:eflow="http://www.ibm.com/wbi/2005/eflow"
                    %s nsURI="%s">
                """.formatted(declarations, nsUri);
    }

    private String esqlModule(String schema, String module, String field) {
        return """
                BROKER SCHEMA %s
                CREATE COMPUTE MODULE %s
                  CREATE FUNCTION Main() RETURNS BOOLEAN
                  BEGIN
                    SET OutputRoot.XMLNSC.Order.%s = 'value';
                    RETURN TRUE;
                  END;
                END MODULE;
                """.formatted(schema, module, field);
    }

    private List<Path> routeFiles(Path output) throws Exception {
        try (Stream<Path> files = Files.list(output)) {
            return files.filter(path -> path.getFileName().toString().endsWith("-route.xml")).toList();
        }
    }

    private String readRoutes(Path output) throws Exception {
        StringBuilder all = new StringBuilder();
        for (Path route : routeFiles(output)) all.append(Files.readString(route));
        return all.toString();
    }

    private int occurrences(String text, String fragment) {
        return (text.length() - text.replace(fragment, "").length()) / fragment.length();
    }
}
