package ru.petrovov.iib_camel.converter.analysis;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.petrovov.iib_camel.converter.analysis.model.FlowDocument;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.EndpointUsage;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.LinkConfidence;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.Reachability;
import ru.petrovov.iib_camel.converter.analysis.model.PropertyValue.Origin;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlowParser;

import static org.junit.jupiter.api.Assertions.*;

class ProjectAnalyzerTest {
    private final ProjectAnalyzer analyzer = new ProjectAnalyzer();

    @Test
    void appliesPromotedValuesPerInstanceWithoutChangingDefinition() {
        FlowDocument child = document("lib/Relay.subflow", "lib/Relay.subflow", """
                <eStructuralFeatures xmi:type="ecore:EAttribute" xmi:id="Property.target" name="target" defaultValueLiteral="DEMO.DEFAULT"/>
                <nodes xmi:type="eflow:FCMSource" xmi:id="InTerminal.Input"/>
                <nodes xmi:type="mqOut:FCMComposite_1" xmi:id="Send" destinationQueueManagerName="QM_A"/>
                <nodes xmi:type="eflow:FCMSink" xmi:id="OutTerminal.Output"/>
                <attributeLinks promotedAttribute="Property.target" overriddenNodes="Send">
                  <overriddenAttribute href="ComIbmMQOutput.msgnode#Property.queueName"/>
                </attributeLinks>
                """, edge("InTerminal.Input", "out", "Send", "in") + edge("Send", "out", "OutTerminal.Output", "in"));
        FlowDocument parent = document("Main.msgflow", "Main.msgflow", input("Start", "DEMO.IN", "QM_A") + """
                <nodes xmi:type="relay:FCMComposite_1" xmi:id="First" target="DEMO.FIRST"/>
                <nodes xmi:type="relay:FCMComposite_1" xmi:id="Second" target="DEMO.SECOND"/>
                """, edge("Start", "out", "First", "Input") + edge("First", "Output", "Second", "Input"));

        ProjectAnalysis result = analyzer.analyze(List.of(parent, child));
        List<EndpointUsage> sends = result.endpoints().stream()
                .filter(usage -> usage.flowId().equals(parent.file()))
                .filter(usage -> usage.endpoint().role().name().equals("PRODUCE")).toList();

        assertEquals(List.of("DEMO.FIRST", "DEMO.SECOND"), sends.stream()
                .map(usage -> usage.endpoint().destination().value()).toList());
        assertTrue(sends.stream().allMatch(usage -> usage.endpoint().destination().origin() == Origin.RESOLVED));
        assertTrue(sends.stream().allMatch(usage -> usage.reachability() == Reachability.STRUCTURAL_NORMAL));
        assertEquals("DEMO.DEFAULT", endpoint(result, child.file(), "DEMO.DEFAULT").endpoint().destination().value());
        assertEquals(2, result.flows().stream().filter(flow -> !flow.subflow()).findFirst().orElseThrow().subflows().size());
        assertTrue(sends.getFirst().endpoint().destination().sources().stream().anyMatch(source -> source.file().equals(parent.file())));
        assertTrue(sends.getFirst().endpoint().destination().sources().stream().anyMatch(source -> source.file().equals(child.file())));
    }

    @Test
    void preservesSeparateInputAndOutputPortsInsteadOfCrossConnectingBranches() {
        FlowDocument child = document("Ports.subflow", "Ports.subflow", """
                <nodes xmi:type="eflow:FCMSource" xmi:id="InTerminal.Left"/>
                <nodes xmi:type="eflow:FCMSource" xmi:id="InTerminal.Right"/>
                <nodes xmi:type="eflow:FCMSink" xmi:id="OutTerminal.Left"/>
                <nodes xmi:type="eflow:FCMSink" xmi:id="OutTerminal.Right"/>
                """, edge("InTerminal.Left", "out", "OutTerminal.Left", "in")
                        + edge("InTerminal.Right", "out", "OutTerminal.Right", "in"));
        FlowDocument parent = document("Main.msgflow", "Main.msgflow", input("Start", "DEMO.IN", "QM_A") + """
                <nodes xmlns:ports="Ports.subflow" xmi:type="ports:FCMComposite_1" xmi:id="Child"/>
                """ + output("Left", "DEMO.LEFT", "QM_A") + output("Right", "DEMO.RIGHT", "QM_A"),
                edge("Start", "out", "Child", "Left") + edge("Child", "Left", "Left", "in")
                        + edge("Child", "Right", "Right", "in"));

        ProjectAnalysis result = analyzer.analyze(List.of(child, parent));

        assertEquals(Reachability.STRUCTURAL_NORMAL, endpoint(result, parent.file(), "DEMO.LEFT").reachability());
        assertEquals(Reachability.UNCONNECTED, endpoint(result, parent.file(), "DEMO.RIGHT").reachability());
        assertTrue(result.diagnostics().stream().noneMatch(issue -> issue.code().equals("UNRESOLVED_TERMINAL")));
    }

    @Test
    void reportsUnknownSubflowPortWithoutChoosingTheFirstPort() {
        FlowDocument child = document("Ports.subflow", "Ports.subflow", """
                <nodes xmi:type="eflow:FCMSource" xmi:id="InTerminal.Left"/>
                <nodes xmi:type="eflow:FCMSource" xmi:id="InTerminal.Right"/>
                """, "");
        FlowDocument parent = document("Main.msgflow", "Main.msgflow", input("Start", "DEMO.IN", "QM_A") + """
                <nodes xmlns:ports="Ports.subflow" xmi:type="ports:FCMComposite_1" xmi:id="Child"/>
                """, edge("Start", "out", "Child", "Missing"));

        ProjectAnalysis result = analyzer.analyze(List.of(parent, child));

        assertTrue(result.diagnostics().stream().anyMatch(issue -> issue.code().equals("UNRESOLVED_TERMINAL")));
        assertTrue(result.flows().stream().filter(flow -> flow.file().equals(parent.file())).findFirst().orElseThrow()
                .nodes().stream().anyMatch(node -> node.kind().equals("UnresolvedTerminal")));
    }

    @Test
    void keepsAmbiguousReferencesAndQualifiedMissingReferencesUnresolved() {
        FlowDocument first = document("a/Shared.subflow", "a/Shared.subflow", "", "");
        FlowDocument second = document("b/Shared.subflow", "b/Shared.subflow", "", "");
        FlowDocument parent = document("Main.msgflow", "Main.msgflow", """
                <nodes xmlns:shared="Shared.subflow" xmi:type="shared:FCMComposite_1" xmi:id="Ambiguous"/>
                <nodes xmlns:missing="missing/Shared.subflow" xmi:type="missing:FCMComposite_1" xmi:id="Missing"/>
                """, "");

        ProjectAnalysis result = analyzer.analyze(List.of(parent, first, second));

        assertTrue(result.diagnostics().stream().anyMatch(issue -> issue.code().equals("AMBIGUOUS_SUBFLOW")));
        assertTrue(result.diagnostics().stream().anyMatch(issue -> issue.code().equals("MISSING_SUBFLOW")));
    }

    @Test
    void terminatesSubflowCyclesWithAnOpaqueNodeAndDiagnostic() {
        FlowDocument first = document("First.subflow", "First.subflow", """
                <nodes xmlns:child="Second.subflow" xmi:type="child:FCMComposite_1" xmi:id="Second"/>
                """, "");
        FlowDocument second = document("Second.subflow", "Second.subflow", """
                <nodes xmlns:child="First.subflow" xmi:type="child:FCMComposite_1" xmi:id="First"/>
                """, "");

        ProjectAnalysis result = analyzer.analyze(List.of(first, second));

        assertEquals(2, result.diagnostics().stream().filter(issue -> issue.code().equals("SUBFLOW_CYCLE")).count());
        assertTrue(result.flows().stream().allMatch(flow -> flow.nodes().size() == 1));
    }

    @Test
    void matchesBrokerContextAndNeverTreatsSubflowDefinitionAsRunningFlow() {
        FlowDocument producer = document("Producer.msgflow", "Producer.msgflow", input("Start", "DEMO.IN", "QM_A")
                + output("Send", "DEMO.NEXT", "QM_A"), edge("Start", "out", "Send", "in"));
        FlowDocument consumer = document("Consumer.msgflow", "Consumer.msgflow", input("Start", "DEMO.NEXT", "QM_A"), "");
        FlowDocument otherBroker = document("Other.msgflow", "Other.msgflow", input("Start", "DEMO.NEXT", "QM_B"), "");
        FlowDocument template = document("Template.subflow", "Template.subflow", input("Start", "DEMO.NEXT", "QM_A"), "");

        ProjectAnalysis result = analyzer.analyze(List.of(producer, consumer, otherBroker, template));

        assertEquals(1, result.links().size());
        assertEquals(consumer.file(), result.links().getFirst().consumerFlowId());
        assertEquals(LinkConfidence.CONFIGURED, result.links().getFirst().confidence());
    }

    @Test
    void marksNameOnlyMatchAsCandidateAndPreservesCase() {
        FlowDocument producer = document("Producer.msgflow", "Producer.msgflow", input("Start", "DEMO.IN", "QM_A")
                + output("Send", "Demo.Next", ""), edge("Start", "out", "Send", "in"));
        FlowDocument consumer = document("Consumer.msgflow", "Consumer.msgflow", input("Start", "Demo.Next", "QM_A"), "");
        FlowDocument differentCase = document("Case.msgflow", "Case.msgflow", input("Start", "DEMO.NEXT", "QM_A"), "");

        ProjectAnalysis result = analyzer.analyze(List.of(producer, consumer, differentCase));

        assertEquals(1, result.links().size());
        assertEquals(LinkConfidence.CANDIDATE, result.links().getFirst().confidence());
    }

    @Test
    void distinguishesErrorAndMixedPathsAndHandlesGraphCycles() {
        FlowDocument document = document("Paths.msgflow", "Paths.msgflow", input("Start", "DEMO.IN", "QM_A")
                + output("Normal", "DEMO.NORMAL", "QM_A") + output("Error", "DEMO.ERROR", "QM_A")
                + output("Mixed", "DEMO.MIXED", "QM_A"),
                edge("Start", "out", "Normal", "in") + edge("Start", "catch", "Error", "in")
                        + edge("Normal", "out", "Mixed", "in") + edge("Error", "out", "Mixed", "in")
                        + edge("Mixed", "out", "Mixed", "in"));

        ProjectAnalysis result = analyzer.analyze(List.of(document));

        assertEquals(Reachability.STRUCTURAL_NORMAL, endpoint(result, document.file(), "DEMO.NORMAL").reachability());
        assertEquals(Reachability.STRUCTURAL_ERROR, endpoint(result, document.file(), "DEMO.ERROR").reachability());
        assertEquals(Reachability.STRUCTURAL_MIXED, endpoint(result, document.file(), "DEMO.MIXED").reachability());
    }

    @Test
    void keepsOpaqueHandlersAndUnknownNodesInTheGraphAndRedactsCredentials() {
        FlowDocument document = document("Opaque.msgflow", "Opaque.msgflow", input("Start", "DEMO.IN", "QM_A") + """
                <nodes xmlns:compute="ComIbmCompute.msgnode" xmi:type="compute:FCMComposite_1" xmi:id="Handler"
                  computeExpression="esql://routine/sample#Missing.Main" password="do-not-export"/>
                <nodes xmlns:custom="urn:custom" xmi:type="custom:Node" xmi:id="Custom"/>
                """, edge("Start", "out", "Handler", "in") + edge("Handler", "out1", "Custom", "in"));

        ProjectAnalysis result = analyzer.analyze(List.of(document));

        assertEquals(1, result.flows().getFirst().handlers().size());
        assertTrue(result.diagnostics().stream().anyMatch(issue -> issue.code().equals("UNKNOWN_NODE_TYPE")));
        assertTrue(result.diagnostics().stream().noneMatch(issue -> issue.code().contains("ESQL")));
        assertEquals("[REDACTED]", result.flows().getFirst().nodes().stream()
                .filter(node -> node.nodeId().equals("Handler")).findFirst().orElseThrow().properties().get("password").value());
    }

    @Test
    void reportsConflictingPromotionsWithoutSelectingTheLastValue() {
        FlowDocument child = document("Conflict.subflow", "Conflict.subflow", """
                <eStructuralFeatures xmi:type="ecore:EAttribute" xmi:id="Property.first" name="first" defaultValueLiteral="DEMO.FIRST"/>
                <eStructuralFeatures xmi:type="ecore:EAttribute" xmi:id="Property.second" name="second" defaultValueLiteral="DEMO.SECOND"/>
                <nodes xmi:type="mqOut:FCMComposite_1" xmi:id="Send" destinationQueueManagerName="QM_A"/>
                <attributeLinks promotedAttribute="Property.first" overriddenNodes="Send">
                  <overriddenAttribute href="ComIbmMQOutput.msgnode#Property.queueName"/>
                </attributeLinks>
                <attributeLinks promotedAttribute="Property.second" overriddenNodes="Send">
                  <overriddenAttribute href="ComIbmMQOutput.msgnode#Property.queueName"/>
                </attributeLinks>
                """, "");

        ProjectAnalysis result = analyzer.analyze(List.of(child));

        assertTrue(result.diagnostics().stream().anyMatch(issue -> issue.code().equals("AMBIGUOUS_PROPERTY_LINK")));
        assertEquals(Origin.UNKNOWN, result.endpoints().getFirst().endpoint().destination().origin());
    }

    @Test
    void missingSubflowDoesNotEstablishNormalReachabilityOrConfiguredLink() {
        FlowDocument parent = document("Main.msgflow", "Main.msgflow", input("Start", "DEMO.IN", "QM_A") + """
                <nodes xmlns:missing="Missing.subflow" xmi:type="missing:FCMComposite_1" xmi:id="Missing"/>
                """ + output("Send", "DEMO.NEXT", "QM_A"),
                edge("Start", "out", "Missing", "Left") + edge("Missing", "Right", "Send", "in"));
        FlowDocument consumer = document("Consumer.msgflow", "Consumer.msgflow", input("Start", "DEMO.NEXT", "QM_A"), "");

        ProjectAnalysis result = analyzer.analyze(List.of(parent, consumer));

        assertEquals(Reachability.UNKNOWN, endpoint(result, parent.file(), "DEMO.NEXT").reachability());
        assertEquals(LinkConfidence.CANDIDATE, result.links().getFirst().confidence());
    }

    @Test
    void unresolvedInputBoundaryDoesNotDeclareDownstreamOutputDisconnected() {
        FlowDocument child = document("Ports.subflow", "Ports.subflow", """
                <nodes xmi:type="eflow:FCMSource" xmi:id="InTerminal.Left"/>
                <nodes xmi:type="eflow:FCMSink" xmi:id="OutTerminal.Right"/>
                """, edge("InTerminal.Left", "out", "OutTerminal.Right", "in"));
        FlowDocument parent = document("Main.msgflow", "Main.msgflow", input("Start", "DEMO.IN", "QM_A") + """
                <nodes xmlns:ports="Ports.subflow" xmi:type="ports:FCMComposite_1" xmi:id="Child"/>
                """ + output("Send", "DEMO.NEXT", "QM_A"),
                edge("Start", "out", "Child", "Missing") + edge("Child", "Right", "Send", "in"));

        ProjectAnalysis result = analyzer.analyze(List.of(parent, child));

        assertEquals(Reachability.UNKNOWN, endpoint(result, parent.file(), "DEMO.NEXT").reachability());
    }

    private static EndpointUsage endpoint(ProjectAnalysis result, String flow, String destination) {
        return result.endpoints().stream().filter(usage -> usage.flowId().equals(flow))
                .filter(usage -> usage.endpoint().destination().value().equals(destination)).findFirst().orElseThrow();
    }

    private static String input(String id, String queue, String manager) {
        return "<nodes xmi:type=\"mqIn:FCMComposite_1\" xmi:id=\"" + id + "\" queueName=\"" + queue
                + "\" destinationQueueManagerName=\"" + manager + "\"/>";
    }

    private static String output(String id, String queue, String manager) {
        return "<nodes xmi:type=\"mqOut:FCMComposite_1\" xmi:id=\"" + id + "\" queueName=\"" + queue
                + "\" destinationQueueManagerName=\"" + manager + "\"/>";
    }

    private static String edge(String source, String terminal, String target, String input) {
        return "<connections sourceNode=\"" + source + "\" sourceTerminalName=\"OutTerminal." + terminal
                + "\" targetNode=\"" + target + "\" targetTerminalName=\"InTerminal." + input + "\"/>";
    }

    private static FlowDocument document(String file, String name, String nodes, String edges) {
        String xml = """
                <ecore:EPackage xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore"
                  xmlns:xmi="http://www.omg.org/XMI" xmlns:eflow="http://www.ibm.com/wbi/2005/eflow"
                  xmlns:mqIn="ComIbmMQInput.msgnode" xmlns:mqOut="ComIbmMQOutput.msgnode"
                  xmlns:relay="lib/Relay.subflow" nsURI="%s">
                  <eClassifiers xmi:type="eflow:FCMComposite" name="FCMComposite_1">
                  %s
                  <composition>%s</composition>
                  </eClassifiers>
                </ecore:EPackage>
                """.formatted(name, nodes, edges);
        return new MsgFlowParser().parseDocument(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), file);
    }
}
