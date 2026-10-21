package com.example.iib_camel.converter.diagram;

import com.example.iib_camel.converter.msgflow.FlowNode;
import com.example.iib_camel.converter.msgflow.MsgFlow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AsciiDoc-документ: IR → .adoc (решение D-8 дизайн-документа).
 */
class AsciiDocDiagramRendererTest {

    private final AsciiDocDiagramRenderer renderer = new AsciiDocDiagramRenderer();

    private MsgFlow adapterLikeFlow() {
        FlowNode input = new FlowNode("Input", "MQInput", "Adapter Input",
                Map.of("queueName", "ADAPTER.IN"), List.of());
        FlowNode compute = new FlowNode("Enrich", "Compute", "Stamp MQMD",
                Map.of("computeExpression", "esql://routine/adapter#Adapter_Compute1.Main"), List.of());
        FlowNode route = new FlowNode("RouteByStatus", "Route", "Route by status",
                Map.of(), List.of("Match"));
        FlowNode output = new FlowNode("DefaultQueue", "MQOutput", "Regular orders",
                Map.of("queueName", "ADAPTER.DEFAULT.Q"), List.of());

        return new MsgFlow("Adapter.msgflow",
                List.of(input, compute, route, output),
                List.of(
                        new com.example.iib_camel.converter.msgflow.FlowConnection("Input", "out", "Enrich", "in"),
                        new com.example.iib_camel.converter.msgflow.FlowConnection("Enrich", "out", "RouteByStatus", "in"),
                        new com.example.iib_camel.converter.msgflow.FlowConnection("RouteByStatus", "Default", "DefaultQueue", "in")));
    }

    @Test
    void documentStructureIsComplete() {
        String doc = renderer.render(adapterLikeFlow());

        assertTrue(doc.startsWith("// Сгенерировано конвертером iib-camel"));
        assertTrue(doc.contains("= Adapter\n"));
        assertTrue(doc.contains("== Диаграмма\n"));
        assertTrue(doc.contains("MQInput «Adapter Input» [ADAPTER.IN]")); // дерево-конспект в листинге
        assertTrue(doc.contains("== Узлы\n"));
        assertTrue(doc.contains("| Input | MQInput | Adapter Input | queueName = ADAPTER.IN"));
        assertTrue(doc.contains("| RouteByStatus | Default | DefaultQueue | in"));
        assertTrue(doc.contains("== Связи\n"));
    }

    @Test
    void literacyTreeIsNestedInsideListingBlock() {
        String doc = renderer.render(adapterLikeFlow());
        int listing = doc.indexOf("----\n");
        assertTrue(listing > 0);
        assertTrue(doc.indexOf("----\n", listing + 1) > doc.indexOf("MQInput «Adapter Input» [ADAPTER.IN]"));
    }

    @Test
    void pipesInCellsAreEscaped() {
        MsgFlow flow = new MsgFlow("P.msgflow",
                List.of(new FlowNode("X1", "MQInput", "Order | X", Map.of(), List.of())),
                List.of());
        assertTrue(renderer.render(flow).contains("Order &#124; X"));
    }

    @Test
    void missingCaptionShownAsDash() {
        // Route в adapterLikeFlow() без queueName/computeExpression
        String doc = renderer.render(adapterLikeFlow());
        assertTrue(doc.contains("| RouteByStatus | Route | Route by status | —"));
    }

    @Test
    void outputIsDeterministic() {
        assertEquals(renderer.render(adapterLikeFlow()), renderer.render(adapterLikeFlow()));
    }
}
