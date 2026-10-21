package com.example.iib_camel.converter.diagram;

import com.example.iib_camel.converter.msgflow.FlowConnection;
import com.example.iib_camel.converter.msgflow.FlowNode;
import com.example.iib_camel.converter.msgflow.MsgFlow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ASCII-конспект: IR → текстовая «дерево-схема» (решение D-8 дизайн-документа).
 */
class AsciiDiagramRendererTest {

    private final AsciiDiagramRenderer renderer = new AsciiDiagramRenderer();

    /** Синтетический аналог корпуса corr-01 («несложный адаптер»). */
    private MsgFlow adapterLikeFlow() {
        FlowNode input = new FlowNode("Input", "MQInput", "Adapter Input",
                Map.of("queueName", "ADAPTER.IN"), List.of());
        FlowNode compute = new FlowNode("Enrich", "Compute", "Stamp MQMD",
                Map.of("computeExpression", "esql://routine/adapter#Adapter_Compute1.Main"), List.of());
        FlowNode route = new FlowNode("RouteByStatus", "Route", "Route by status",
                Map.of(), List.of("Match"));
        FlowNode priority = new FlowNode("PriorityQueue", "MQOutput", "High priority orders",
                Map.of("queueName", "ADAPTER.PRIORITY.Q"), List.of());
        FlowNode regular = new FlowNode("DefaultQueue", "MQOutput", "Regular orders",
                Map.of("queueName", "ADAPTER.DEFAULT.Q"), List.of());
        FlowNode error = new FlowNode("ErrorQueue", "MQOutput", "Malformed messages",
                Map.of("queueName", "ADAPTER.ERROR.Q"), List.of());

        return new MsgFlow("Adapter.msgflow",
                List.of(input, compute, route, priority, regular, error),
                List.of(
                        new FlowConnection("Input", "out", "Enrich", "in"),
                        new FlowConnection("Enrich", "out", "RouteByStatus", "in"),
                        new FlowConnection("RouteByStatus", "Match", "PriorityQueue", "in"),
                        new FlowConnection("RouteByStatus", "Default", "DefaultQueue", "in"),
                        new FlowConnection("Input", "catch", "ErrorQueue", "in")));
    }

    @Test
    void adapterLikeFlowRendersAsOutline() {
        String diagram = renderer.render(adapterLikeFlow());
        assertEquals("""
                MQInput «Adapter Input» [ADAPTER.IN]
                ├─ out → Compute «Stamp MQMD» [Adapter_Compute1.Main]
                │  └─ out → Route «Route by status»
                │     ├─ Match → MQOutput «High priority orders» [ADAPTER.PRIORITY.Q]
                │     └─ Default → MQOutput «Regular orders» [ADAPTER.DEFAULT.Q]
                └─ catch → MQOutput «Malformed messages» [ADAPTER.ERROR.Q]
                """, diagram);
    }

    @Test
    void cyclesAreSafeAndMarkedAsSeen() {
        FlowNode a = new FlowNode("A1", "Compute", "A", Map.of(), List.of());
        FlowNode b = new FlowNode("B1", "Compute", "B", Map.of(), List.of());
        MsgFlow flow = new MsgFlow("C.msgflow", List.of(a, b),
                List.of(
                        new FlowConnection("A1", "out", "B1", "in"),
                        new FlowConnection("B1", "out", "A1", "in")));

        String diagram = renderer.render(flow);
        assertEquals("""
                Compute «A»
                └─ out → Compute «B»
                   └─ out → Compute «A» (↑ показано выше)
                """, diagram);
    }

    @Test
    void multipleRootsFollowDeclarationOrder() {
        FlowNode first = new FlowNode("F1", "Compute", "First", Map.of(), List.of());
        FlowNode second = new FlowNode("S1", "MQInput", "Second", Map.of(), List.of());
        MsgFlow flow = new MsgFlow("R.msgflow", List.of(first, second), List.of());

        String diagram = renderer.render(flow);
        assertEquals("""
                Compute «First»
                MQInput «Second»
                """, diagram);
    }

    @Test
    void outputIsDeterministic() {
        assertEquals(renderer.render(adapterLikeFlow()), renderer.render(adapterLikeFlow()));
    }
}
