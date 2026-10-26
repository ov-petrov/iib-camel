package ru.petrovov.iib_camel.converter.analysis.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.petrovov.iib_camel.converter.analysis.model.EndpointDescriptor;
import ru.petrovov.iib_camel.converter.analysis.model.EndpointDescriptor.DestinationKind;
import ru.petrovov.iib_camel.converter.analysis.model.EndpointDescriptor.Role;
import ru.petrovov.iib_camel.converter.analysis.model.EndpointDescriptor.Transport;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.Diagnostic;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.EndpointUsage;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.FlowAnalysis;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.FlowLink;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.GraphEdge;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.HandlerReference;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.LinkConfidence;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.NodeInstance;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.Reachability;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.Severity;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.SubflowInstance;
import ru.petrovov.iib_camel.converter.analysis.model.PropertyValue;
import ru.petrovov.iib_camel.converter.analysis.model.SourceRef;
import ru.petrovov.iib_camel.converter.diagram.Orientation;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryRendererTest {

    private final InventoryRenderer renderer = new InventoryRenderer();

    @Test
    void outputsFlowAndDefinitionInventoryWithKnownSourcePositionAndExplicitUnknowns() {
        ProjectAnalysis analysis = analysis();

        Map<String, String> artifacts = renderer.render(analysis, Orientation.LR);
        String overview = artifacts.get("system-overview.md");

        assertTrue(overview.contains("Корневые потоки: **1**; определения под-потоков: **1**"));
        assertTrue(overview.contains("статический структурный инвентарь"));
        assertTrue(overview.contains("UNKNOWN"));
        assertTrue(overview.contains("sample.flow:17:3"));
        assertTrue(overview.contains("CANDIDATE"));
        assertTrue(overview.contains("configuration warning"));
        assertTrue(overview.contains("Skipped.esql"));
        assertTrue(artifacts.containsKey("flow-main-flow-" + suffix(analysis.flows().getFirst()) + ".md"));
        assertTrue(artifacts.containsKey("flow-common-subflow-" + suffix(analysis.flows().getLast()) + ".md"));
    }

    @Test
    void rendersEscapedLabelsAndKeepsFailureAndCatchTerminalNames() {
        ProjectAnalysis analysis = analysis();
        String flowReport = analysis.flows().stream().filter(flow -> flow.id().equals("main")).findFirst()
                .map(InventoryRenderer::flowFileName).orElseThrow();
        String content = renderer.render(analysis, Orientation.TB).get(flowReport);

        assertTrue(content.contains("flowchart TB"));
        assertTrue(content.contains("&#124;"));
        assertTrue(content.contains("&lt;unsafe&gt;"));
        assertTrue(content.contains("failure"));
        assertTrue(content.contains("catch"));
        assertTrue(content.contains("Непрозрачные ссылки на обработчики"));
        assertTrue(content.contains("opaque:DoWork"));
        assertFalse(content.contains("<img"));
        assertTrue(content.contains("Definitions/Common.msgflow"));
    }

    @Test
    void rendersDeterministicallyRegardlessOfNodePropertyMapOrder() {
        FlowAnalysis first = flow("main", "Main Flow", false, Map.of("alpha", prop("a"), "beta", prop("b")));
        Map<String, PropertyValue> reverse = new LinkedHashMap<>();
        reverse.put("beta", prop("b"));
        reverse.put("alpha", prop("a"));
        FlowAnalysis second = flow("main", "Main Flow", false, reverse);

        assertEquals(renderer.render(project(first), Orientation.LR), renderer.render(project(second), Orientation.LR));
    }

    @Test
    void csvQuotesFieldsAndMitigatesFormulaCells() {
        EndpointUsage usage = endpoint("e1", "main", "n1", "=2+2", "broker,\"west\"", Role.FETCH);
        Map<String, String> artifacts = renderer.render(project(flow("main", "Main", false, Map.of()), usage), Orientation.LR);
        String csv = artifacts.get("endpoints.csv");

        assertTrue(csv.contains("\"'=2+2\""));
        assertTrue(csv.contains("\"broker,\"\"west\"\"\""));
        assertTrue(csv.contains("FETCH (выборка MQGet)"));
    }

    @Test
    void jsonIsParseableAndRedactsCredentialLikeProperties() {
        Map<String, PropertyValue> properties = Map.of(
                "passwordValue", prop("do-not-print"),
                "queueName", prop("SAFE.QUEUE"));
        String json = renderer.render(project(flow("main", "Main", false, properties)), Orientation.LR)
                .get("project-inventory.json");
        var parsed = JsonMapper.builder().build().readTree(json);

        assertNotNull(parsed.get("schemaVersion"));
        assertTrue(json.contains("[REDACTED]"));
        assertFalse(json.contains("do-not-print"));
        assertTrue(json.contains("SAFE.QUEUE"));
    }

    @Test
    void filenamesAreSafeAndStableForPunctuationAndNonAsciiNames() {
        FlowAnalysis flow = flow("same-id", "<Queue> / Заказ", false, Map.of());
        String filename = InventoryRenderer.flowFileName(flow);

        assertTrue(filename.matches("flow-[a-z0-9-]+-[0-9a-f]{8}\\.md"));
        assertEquals(filename, InventoryRenderer.flowFileName(flow));
    }

    private static String suffix(FlowAnalysis flow) {
        String filename = InventoryRenderer.flowFileName(flow);
        return filename.substring(filename.lastIndexOf('-') + 1, filename.length() - 3);
    }

    private static ProjectAnalysis analysis() {
        Map<String, PropertyValue> props = new LinkedHashMap<>();
        props.put("text", PropertyValue.declared("<unsafe>| `label`", new SourceRef("sample.flow", 17, 3)));
        FlowAnalysis main = new FlowAnalysis("main", "Flows/Main.msgflow", "Main Flow", false,
                List.of(new NodeInstance("n1", "n1", "main/n1", "Source <unsafe>| `label`", "MQInput", props, new SourceRef("sample.flow", 17, 3)),
                        new NodeInstance("n2", "n2", "main/n2", "Error handler", "Compute", Map.of(), SourceRef.file("sample.flow"))),
                List.of(new GraphEdge("n1", "failure", "n2", "in", new SourceRef("sample.flow", 20, 1)),
                        new GraphEdge("n1", "catch", "n2", "in", new SourceRef("sample.flow", 21, 1))),
                List.of(new SubflowInstance("sf1", "sub-node", "Definitions/Common.msgflow", Map.of("limit", prop("5")), SourceRef.file("sample.flow"))),
                List.of(new HandlerReference("n2", "opaque:DoWork", "shared-ds", SourceRef.file("sample.flow"))),
                List.of("e1", "e2"), List.of(new Diagnostic("D1", Severity.WARNING, "main", "n1", "configuration warning", SourceRef.file("sample.flow"))));
        FlowAnalysis subflow = flow("common", "Common Subflow", true, Map.of());
        EndpointUsage known = endpoint("e1", "main", "n1", "ORDERS.IN", "BROKER.WEST", Role.CONSUME);
        EndpointUsage unknown = new EndpointUsage("e2", "main", "n2", null, Reachability.UNKNOWN, SourceRef.file("sample.flow"));
        FlowLink link = new FlowLink("main", "e1", "common", "e2", LinkConfidence.CANDIDATE, "candidate inferred from broker context");
        return new ProjectAnalysis(1, List.of(main, subflow), List.of(known, unknown), List.of(link),
                List.of(new Diagnostic("D1", Severity.WARNING, "main", "n1", "configuration warning", SourceRef.file("sample.flow"))),
                List.of("Skipped.esql"));
    }

    private static ProjectAnalysis project(FlowAnalysis flow, EndpointUsage... endpoints) {
        return new ProjectAnalysis(1, List.of(flow), List.of(endpoints), List.of(), List.of(), List.of());
    }

    private static FlowAnalysis flow(String id, String name, boolean subflow, Map<String, PropertyValue> properties) {
        return new FlowAnalysis(id, "flows/" + id + ".msgflow", name, subflow,
                List.of(new NodeInstance("n1", "n1", id + "/n1", name, "Compute", properties, SourceRef.file("flows/" + id + ".msgflow"))),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static EndpointUsage endpoint(String id, String flowId, String nodeId, String destination, String broker, Role role) {
        EndpointDescriptor descriptor = new EndpointDescriptor(Transport.MQ, DestinationKind.QUEUE, role,
                PropertyValue.declared(destination, SourceRef.file("sample.flow")),
                PropertyValue.declared(broker, SourceRef.file("sample.flow")), "", "note");
        return new EndpointUsage(id, flowId, nodeId, descriptor, Reachability.STRUCTURAL_NORMAL, new SourceRef("sample.flow", 17, 3));
    }

    private static PropertyValue prop(String value) {
        return PropertyValue.declared(value, SourceRef.file("sample.flow"));
    }
}
