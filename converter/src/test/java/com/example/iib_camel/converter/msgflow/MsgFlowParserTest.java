package com.example.iib_camel.converter.msgflow;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MsgFlowParserTest {

    private final MsgFlowParser parser = new MsgFlowParser();

    private MsgFlow adapterFlow() {
        try (var in = getClass().getResourceAsStream("/corpus/corr-01-simple-adapter/Adapter.msgflow")) {
            return parser.parse(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MsgFlow load(String resource) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            return parser.parse(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void parsesFlowName() {
        assertEquals("Adapter.msgflow", adapterFlow().name());
    }

    @Test
    void parsesAllSixNodes() {
        var flow = adapterFlow();
        assertEquals(6, flow.nodes().size());
        assertEquals(List.of("Input", "Enrich", "RouteByStatus", "PriorityQueue", "DefaultQueue", "ErrorQueue"),
                flow.nodes().stream().map(FlowNode::id).toList());
    }

    @Test
    void derivesNodeKindsFromXmiType() {
        var flow = adapterFlow();
        assertEquals("MQInput", flow.node("Input").kind());
        assertEquals("Compute", flow.node("Enrich").kind());
        assertEquals("Route", flow.node("RouteByStatus").kind());
        assertEquals("MQOutput", flow.node("PriorityQueue").kind());
    }

    @Test
    void parsesSubflowReferenceNodeWithResolvedTarget() {
        var flow = orderFlow();
        var ref = flow.node("CheckRef");
        assertEquals("Subflow", ref.kind(), "канон ACE: <Имя>.subflow:FCMComposite_1 → Subflow");
        assertEquals("Check", ref.properties().get("subflowName"),
                "цель подпотока извлечена из xmi:type (A4)");
        assertEquals("Validate via subflow", ref.label());
    }

    @Test
    void parsesSubflowInputOutputAndTraceKinds() {
        var sub = checkFlow();
        assertEquals("Input", sub.node("SourceFlow").kind(), "eflow:FCMSource → Input");
        assertEquals("Output", sub.node("SinkFlow").kind(), "eflow:FCMSink → Output");
        assertEquals("Trace", sub.node("TraceNode").kind(), "ComIbmTrace.msgnode → Trace");
        assertEquals("Check.subflow", sub.name(), "имя подпотока из nsURI — юнит отчёта");
    }

    private MsgFlow orderFlow() {
        return load("/corpus/corr-02-subflows/Order.msgflow");
    }

    private MsgFlow checkFlow() {
        return load("/corpus/corr-02-subflows/Check.subflow");
    }

    @Test
    void keepsNodePropertiesAndLabel() {
        var flow = adapterFlow();
        var input = flow.node("Input");
        assertEquals("ADAPTER.IN", input.properties().get("queueName"));
        assertEquals("XMLNSC", input.properties().get("messageDomainProperty"));
        assertEquals("Adapter Input", input.label());
    }

    @Test
    void keepsComputeEsqlBinding() {
        var flow = adapterFlow();
        assertEquals("esql://routine/adapter#Adapter_Compute1.Main",
                flow.node("Enrich").properties().get("computeExpression"));
    }

    @Test
    void keepsRouteFilterPatternAndDynamicTerminal() {
        var flow = adapterFlow();
        var route = flow.node("RouteByStatus");
        assertEquals("$Body/Adapter/Order/status='HIGH'",
                route.properties().get("filterTable.filterPattern"));
        assertEquals("Match", route.dynamicOutTerminals().getFirst());
        assertEquals(List.of(new RouteRule("$Body/Adapter/Order/status='HIGH'", "Match")), route.routeRules());
    }

    @Test
    void preservesEveryFilterTableRowInSourceOrderIncludingRepeatedTerminals() {
        String xml = """
                <ecore:EPackage xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore"
                    xmlns:xmi="http://www.omg.org/XMI" nsURI="Rules.msgflow">
                  <nodes xmi:type="ComIbmRoute.msgnode:FCMComposite_1" xmi:id="Route" distributionMode="First">
                    <outTerminals terminalNodeID="Audit"/>
                    <outTerminals terminalNodeID="High"/>
                    <filterTable filterPattern="$Body/Order/amount&gt;100" routingOutputTerminal="High"/>
                    <filterTable filterPattern="$Body/Order/status='HIGH'" routingOutputTerminal="Audit"/>
                    <filterTable filterPattern="$Body/Order/amount&lt;0" routingOutputTerminal="High"/>
                  </nodes>
                </ecore:EPackage>
                """;
        var route = parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).node("Route");
        assertEquals("First", route.properties().get("distributionMode"));
        assertEquals(List.of(new RouteRule("$Body/Order/amount>100", "High"),
                new RouteRule("$Body/Order/status='HIGH'", "Audit"),
                new RouteRule("$Body/Order/amount<0", "High")), route.routeRules());
    }

    @Test
    void normalizesTerminalNames() {
        var flow = adapterFlow();
        var connections = flow.connections();
        assertEquals(5, connections.size());
        assertTrue(connections.stream().anyMatch(c ->
                c.sourceNodeId().equals("Input") && c.sourceTerminal().equals("out")
                        && c.targetNodeId().equals("Enrich") && c.targetTerminal().equals("in")));
        assertTrue(connections.stream().anyMatch(c ->
                c.sourceNodeId().equals("RouteByStatus") && c.sourceTerminal().equals("Match")
                        && c.targetNodeId().equals("PriorityQueue")));
        assertTrue(connections.stream().anyMatch(c ->
                c.sourceNodeId().equals("RouteByStatus") && c.sourceTerminal().equals("Default")
                        && c.targetNodeId().equals("DefaultQueue")));
        assertTrue(connections.stream().anyMatch(c ->
                c.sourceNodeId().equals("Input") && c.sourceTerminal().equals("catch")
                        && c.targetNodeId().equals("ErrorQueue")));
    }

    @Test
    void rejectsNonMsgflowDocument() {
        var fake = new InputStream() {
            @Override
            public int read() {
                return -1;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> parser.parse(fake));
    }

    @Test
    void parsesRenamedXmiAndClassifierPrefixesIntoTheSameIr() {
        String original = readResource("/corpus/corr-02-subflows/Check.subflow");
        String renamed = original
                .replace("xmlns:xmi=", "xmlns:meta=")
                .replace("xmi:", "meta:")
                .replace("xmlns:eflow=", "xmlns:engine=")
                .replace("eflow:", "engine:")
                .replace("xmlns:ComIbmTrace.msgnode=\"ComIbmTrace.msgnode\"", "xmlns:traceNode=\"ComIbmTrace.msgnode\"")
                .replace("ComIbmTrace.msgnode:", "traceNode:");
        assertEquals(parse(original), parse(renamed));
    }

    @Test
    void parsesNamespacedStructuralChildElementsByLocalName() {
        String xml = """
                <ecore:EPackage xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore"
                    xmlns:meta="http://www.omg.org/XMI" xmlns:engine="http://www.ibm.com/wbi/2005/eflow"
                    xmlns:mq="ComIbmMQInput.msgnode" xmlns:util="urn:utility" nsURI="Namespaced.msgflow">
                  <engine:nodes meta:type="mq:FCMComposite_1" meta:id="Input" queueName="IN">
                    <util:translation string="Namespaced input"/>
                    <engine:outTerminals terminalNodeID="out"/>
                  </engine:nodes>
                  <engine:nodes meta:type="mq:FCMComposite_1" meta:id="Output"/>
                  <engine:connections sourceNode="Input" targetNode="Output"
                    sourceTerminalName="out" targetTerminalName="in"/>
                </ecore:EPackage>
                """;
        var flow = parse(xml);
        assertEquals("MQInput", flow.node("Input").kind());
        assertEquals("Namespaced input", flow.node("Input").label());
        assertEquals("IN", flow.node("Input").properties().get("queueName"));
        assertEquals(1, flow.connections().size());
    }

    @Test
    void rejectsDuplicateNodeIdsAndDanglingConnections() {
        String duplicate = minimalFlow("<nodes xmi:type=\"mq:FCMComposite_1\" xmi:id=\"same\"/><nodes xmi:type=\"mq:FCMComposite_1\" xmi:id=\"same\"/>", "");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse(duplicate))
                .getMessage().contains("повторяющийся xmi:id узла"));

        String dangling = minimalFlow("<nodes xmi:type=\"mq:FCMComposite_1\" xmi:id=\"one\"/>",
                "<connections sourceNode=\"one\" targetNode=\"missing\" sourceTerminalName=\"out\" targetTerminalName=\"in\"/>");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse(dangling))
                .getMessage().contains("отсутствующий targetNode"));
    }

    @Test
    void rejectsDuplicateConnectionIdsAndBlankTerminalReferences() {
        String duplicate = minimalFlow(
                "<nodes xmi:type=\"mq:FCMComposite_1\" xmi:id=\"one\"/><nodes xmi:type=\"mq:FCMComposite_1\" xmi:id=\"two\"/>",
                "<connections xmi:id=\"edge\" sourceNode=\"one\" targetNode=\"two\" sourceTerminalName=\"out\" targetTerminalName=\"in\"/><connections xmi:id=\"edge\" sourceNode=\"one\" targetNode=\"two\" sourceTerminalName=\"out\" targetTerminalName=\"in\"/>");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse(duplicate))
                .getMessage().contains("повторяющийся xmi:id связи"));

        String blank = minimalFlow("<nodes xmi:type=\"mq:FCMComposite_1\" xmi:id=\"one\"/><nodes xmi:type=\"mq:FCMComposite_1\" xmi:id=\"two\"/>",
                "<connections sourceNode=\"one\" targetNode=\"two\" sourceTerminalName=\" \" targetTerminalName=\"in\"/>");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse(blank))
                .getMessage().contains("sourceTerminalName"));
    }

    @Test
    void rejectsMalformedXmlAndExternalDtd() {
        assertThrows(IllegalArgumentException.class, () -> parse("<ecore:EPackage"));
        String dtd = "<!DOCTYPE ecore:EPackage [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"
                + "<ecore:EPackage xmlns:ecore=\"http://www.eclipse.org/emf/2002/Ecore\" nsURI=\"&x;\"/>";
        assertThrows(IllegalArgumentException.class, () -> parse(dtd));
    }

    private MsgFlow parse(String xml) {
        return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private String readResource(String resource) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String minimalFlow(String nodes, String connections) {
        return """
                <ecore:EPackage xmlns:ecore="http://www.eclipse.org/emf/2002/Ecore"
                    xmlns:xmi="http://www.omg.org/XMI" xmlns:mq="ComIbmMQInput.msgnode" nsURI="Test.msgflow">
                  %s
                  %s
                </ecore:EPackage>
                """.formatted(nodes, connections);
    }
}
