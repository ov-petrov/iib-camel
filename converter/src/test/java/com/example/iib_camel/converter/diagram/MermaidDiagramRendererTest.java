package com.example.iib_camel.converter.diagram;

import com.example.iib_camel.converter.msgflow.FlowConnection;
import com.example.iib_camel.converter.msgflow.FlowNode;
import com.example.iib_camel.converter.msgflow.MsgFlow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MermaidDiagramRendererTest {

    private final MermaidDiagramRenderer renderer =
            new MermaidDiagramRenderer(NodeCategoryRegistry.withIibDefaults());

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
    void nodeLinesFollowDeclarationOrder() {
        String diagram = renderer.render(adapterLikeFlow());
        assertTrue(diagram.contains("N1[\"Adapter Input<br/>MQInput<br/>ADAPTER.IN\"]:::input"));
        assertTrue(diagram.contains("N2[\"Stamp MQMD<br/>Compute<br/>Adapter_Compute1.Main\"]:::process"));
        assertTrue(diagram.contains("N3[\"Route by status<br/>Route\"]:::route"));
        assertTrue(diagram.contains("N4[\"High priority orders<br/>MQOutput<br/>ADAPTER.PRIORITY.Q\"]:::output"));
    }

    @Test
    void edgeCaptioningFollowsD5Rule() {
        String diagram = renderer.render(adapterLikeFlow());
        assertTrue(diagram.contains("N1 --> N2"));
        assertTrue(diagram.contains("N3 --> N4"));
        assertTrue(diagram.contains("N3 --> N5"));
        assertTrue(diagram.contains("N1 -. catch .-> N6"));
    }

    @Test
    void classDefsAreEmittedForUsedCategoriesInEnumOrder() {
        String diagram = renderer.render(adapterLikeFlow());
        int inputIdx = diagram.indexOf("classDef input ");
        int outputIdx = diagram.indexOf("classDef output ");
        int processIdx = diagram.indexOf("classDef process ");
        assertTrue(inputIdx >= 0 && outputIdx > inputIdx && processIdx > outputIdx);
        assertTrue(diagram.contains("classDef input fill:#e8eaf6,stroke:#3949ab,stroke-width:1px"));
    }

    @Test
    void subflowNodeUsesReferenceShape() {
        MsgFlow flow = new MsgFlow("Check.msgflow",
                List.of(new FlowNode("S1", "Subflow", "CheckStatus", Map.of(), List.of())),
                List.of());
        String diagram = renderer.render(flow);
        assertTrue(diagram.contains("N1[[\"CheckStatus (subflow)<br/>Subflow\"]]:::reference"));
        assertTrue(diagram.contains("classDef reference fill:#f9fbe7,stroke:#827717,stroke-width:1px"));
    }

    @Test
    void unknownKindFallsBackToOther() {
        MsgFlow flow = new MsgFlow("E.msgflow",
                List.of(new FlowNode("X1", "CustomWeird", "Exotic", Map.of(), List.of())),
                List.of());
        String diagram = renderer.render(flow);
        assertTrue(diagram.contains("N1[\"Exotic<br/>CustomWeird\"]:::other"));
        assertTrue(diagram.contains("classDef other fill:#eceff1,stroke:#37474f,stroke-width:1px"));
    }

    @Test
    void labelsAreEscapedAndBlankLabelFallsBackToId() {
        MsgFlow flow = new MsgFlow("E.msgflow",
                List.of(
                        new FlowNode("Q1", "MQInput", "Order \"A\" #12", Map.of(), List.of()),
                        new FlowNode("Q2", "Compute", "", Map.of(), List.of())),
                List.of());
        String diagram = renderer.render(flow);
        assertTrue(diagram.contains("Order &quot;A&quot; &num;12"));
        assertTrue(diagram.contains("N2[\"Q2<br/>Compute\"]:::process"));
    }

    @Test
    void orientationIsHonored() {
        String diagram = renderer.render(adapterLikeFlow(), Orientation.TB);
        assertTrue(diagram.startsWith("flowchart TB"));
    }

    @Test
    void outputIsByteDeterministic() {
        assertEquals(renderer.render(adapterLikeFlow()), renderer.render(adapterLikeFlow()));
    }
}
