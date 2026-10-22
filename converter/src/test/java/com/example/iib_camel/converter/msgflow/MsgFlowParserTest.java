package com.example.iib_camel.converter.msgflow;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Юнит-тесты парсера .msgflow на fixture corr-01-simple-adapter (уровень L1 стратегии тестирования). */
class MsgFlowParserTest {

    private final MsgFlowParser parser = new MsgFlowParser();

    private MsgFlow adapterFlow() {
        try (var in = getClass().getResourceAsStream("/corpus/corr-01-simple-adapter/Adapter.msgflow")) {
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
        // ветка catch входного узла — обработка ошибок (FR-2.3)
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
}
