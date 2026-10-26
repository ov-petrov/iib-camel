package ru.petrovov.iib_camel.converter.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryCommandTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private int run(String... args) {
        return new CommandLine(new AnalyzeCommand()).execute(args);
    }

    private static Path exampleDirectory() {
        Path relative = Path.of("examples", "analyze", "inventory");
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve(relative);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Could not locate " + relative + " from the current directory");
    }

    private static Path copyExamples(Path target) throws IOException {
        Path source = exampleDirectory();
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Files.copy(file, target.resolve(source.relativize(file)));
            }
        }
        return target;
    }

    private static Map<String, String> artifacts(Path output) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(output)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith(".iib-camel-"))
                    .sorted().toList()) {
                result.put(file.getFileName().toString(), Files.readString(file));
            }
        }
        return result;
    }

    @Test
    void inventoriesFreshMultiFlowArtifactsWithoutParsingEsql(@TempDir Path temp) throws Exception {
        Path input = copyExamples(Files.createDirectory(temp.resolve("input")));
        Files.writeString(input.resolve("invalid.esql"), "not valid ESQL { deliberately");
        Path output = temp.resolve("output");

        assertEquals(0, run("--inventory", "--input", input.toString(), "--output", output.toString()));

        assertTrue(Files.isRegularFile(output.resolve("system-overview.md")));
        assertTrue(Files.isRegularFile(output.resolve("endpoints.csv")));
        Path jsonFile = output.resolve("project-inventory.json");
        assertTrue(Files.isRegularFile(jsonFile));
        JsonNode inventory = JSON.readTree(Files.readString(jsonFile));
        assertEquals(1, inventory.path("schemaVersion").intValue());
        assertEquals("invalid.esql", inventory.path("skippedEsqlFiles").get(0).textValue());
        assertFalse(Files.exists(output.resolve("esql-inventory.md")));

        JsonNode flows = inventory.path("flows");
        JsonNode publisher = findFlow(flows, "Publisher.msgflow");
        assertNotNull(publisher);
        assertTrue(publisher.isObject());
        assertEquals(2, publisher.path("subflows").size());
        assertTrue(publisher.path("handlers").toString().contains("esql://routine/missing#Transform.Main"));

        String json = Files.readString(jsonFile);
        assertTrue(json.contains("DEMO.DEFAULT"));
        assertTrue(json.contains("DEMO.NEXT"));
        assertTrue(json.contains("DEMO.AUDIT"));
        assertTrue(json.contains("DEMO.CACHE"));
        assertTrue(json.contains("DEMO.DONE"));
        assertTrue(json.contains("DEMO.events"));
        assertTrue(json.contains("QM_OTHER"));

        JsonNode endpoints = inventory.path("endpoints");
        assertTrue(hasEndpoint(endpoints, "DEMO.NEXT", "QM_DEMO"));
        assertTrue(hasEndpoint(endpoints, "DEMO.AUDIT", "QM_DEMO"));
        assertTrue(hasEndpoint(endpoints, "DEMO.events", "broker.demo:9092"));
        assertFalse(inventory.path("links").toString().contains("OtherBroker"));
        assertTrue(Files.readString(output.resolve("system-overview.md")).contains("ESQL не разбирается"));
        assertTrue(Files.readString(output.resolve("system-overview.md")).contains("invalid.esql"));
    }

    @Test
    void malformedFlowStillPublishesValidReportsAndReturnsTwo(@TempDir Path temp) throws Exception {
        Path input = copyExamples(Files.createDirectory(temp.resolve("input")));
        Files.writeString(input.resolve("Malformed.msgflow"), "<ecore:EPackage");
        Path output = temp.resolve("output");

        assertEquals(2, run("--inventory", "--input", input.toString(), "--output", output.toString()));

        assertTrue(Files.isRegularFile(output.resolve("system-overview.md")));
        JsonNode inventory = JSON.readTree(Files.readString(output.resolve("project-inventory.json")));
        assertTrue(inventory.path("diagnostics").toString().contains("SOURCE_PARSE_ERROR"));
        assertTrue(inventory.path("diagnostics").toString().contains("ERROR"));
        assertTrue(inventory.path("flows").size() > 0);
    }

    @Test
    void strictWarningsReturnTwoAndStillPublishInventory(@TempDir Path temp) throws Exception {
        Path input = Files.createDirectory(temp.resolve("input"));
        Files.writeString(input.resolve("Unknown.msgflow"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <ecore:EPackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI"
                    xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore"
                    xmlns:eflow="http://www.ibm.com/wbi/2005/eflow"
                    xmlns:ComIbmMystery.msgnode="ComIbmMystery.msgnode"
                    nsURI="Unknown.msgflow" nsPrefix="Unknown.msgflow">
                  <eClassifiers xmi:type="eflow:FCMComposite" name="FCMComposite_1">
                    <composition>
                      <nodes xmi:type="ComIbmMystery.msgnode:FCMComposite_1" xmi:id="Mystery"/>
                    </composition>
                  </eClassifiers>
                </ecore:EPackage>
                """);
        Path output = temp.resolve("output");

        assertEquals(2, run("--inventory", "--strict", "--input", input.toString(), "--output", output.toString()));

        assertTrue(Files.isRegularFile(output.resolve("project-inventory.json")));
        JsonNode inventory = JSON.readTree(Files.readString(output.resolve("project-inventory.json")));
        assertTrue(inventory.path("diagnostics").toString().contains("WARNING"));
    }

    @Test
    void allMalformedInputsReturnThreeAndPublishErrorInventory(@TempDir Path temp) throws Exception {
        Path input = Files.createDirectory(temp.resolve("input"));
        Files.writeString(input.resolve("Broken.msgflow"), "<ecore:EPackage");
        Path output = temp.resolve("output");

        assertEquals(3, run("--inventory", "--input", input.toString(), "--output", output.toString()));

        JsonNode inventory = JSON.readTree(Files.readString(output.resolve("project-inventory.json")));
        assertEquals(0, inventory.path("flows").size());
        assertTrue(inventory.path("diagnostics").toString().contains("SOURCE_PARSE_ERROR"));
    }

    @Test
    void generatedArtifactsAreDeterministicAcrossRuns(@TempDir Path temp) throws Exception {
        Path input = copyExamples(Files.createDirectory(temp.resolve("input")));
        Path first = temp.resolve("first");
        Path second = temp.resolve("second");

        assertEquals(0, run("--inventory", "--input", input.toString(), "--output", first.toString()));
        assertEquals(0, run("--inventory", "--input", input.toString(), "--output", second.toString()));

        assertEquals(artifacts(first), artifacts(second));
    }

    @Test
    void manuallyEditedGeneratedFileIsPreservedWhenPublishingFails(@TempDir Path temp) throws Exception {
        Path input = copyExamples(Files.createDirectory(temp.resolve("input")));
        Path output = temp.resolve("output");
        assertEquals(0, run("--inventory", "--input", input.toString(), "--output", output.toString()));
        Path edited = output.resolve("system-overview.md");
        Files.writeString(edited, "manual edit");

        assertEquals(3, run("--inventory", "--input", input.toString(), "--output", output.toString()));

        assertEquals("manual edit", Files.readString(edited));
    }

    @Test
    void singleFileInputIsReadOnlyAndWritesOnlyToOutput(@TempDir Path temp) throws Exception {
        Path input = temp.resolve("One.msgflow");
        Files.copy(exampleDirectory().resolve("Consumer.msgflow"), input);
        String original = Files.readString(input);
        Path output = temp.resolve("output");

        assertEquals(0, run("--inventory", "--input", input.toString(), "--output", output.toString()));

        assertEquals(original, Files.readString(input));
        assertTrue(Files.isRegularFile(output.resolve("project-inventory.json")));
        assertFalse(Files.exists(temp.resolve("One.md")));
    }

    private static JsonNode findFlow(JsonNode flows, String file) {
        for (JsonNode flow : flows) {
            if (file.equals(flow.path("file").textValue())) {
                return flow;
            }
        }
        return null;
    }

    private static boolean hasEndpoint(JsonNode endpoints, String destination, String broker) {
        for (JsonNode usage : endpoints) {
            JsonNode endpoint = usage.path("endpoint");
            if (destination.equals(endpoint.path("destination").path("value").textValue())
                    && broker.equals(endpoint.path("broker").path("value").textValue())) {
                return true;
            }
        }
        return false;
    }
}
