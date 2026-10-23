package ru.petrovov.iib_camel.converter.diagram;

import org.junit.jupiter.api.Test;
import ru.petrovov.iib_camel.converter.msgflow.FlowNode;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlow;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
                        new ru.petrovov.iib_camel.converter.msgflow.FlowConnection("Input", "out", "Enrich", "in"),
                        new ru.petrovov.iib_camel.converter.msgflow.FlowConnection("Enrich", "out", "RouteByStatus", "in"),
                        new ru.petrovov.iib_camel.converter.msgflow.FlowConnection("RouteByStatus", "Default", "DefaultQueue", "in")));
    }

    @Test
    void documentStructureIsComplete() {
        String doc = renderer.render(adapterLikeFlow());

        assertTrue(doc.startsWith("= Adapter\n"));
        assertFalse(doc.contains("//"));
        assertTrue(doc.contains("= Adapter\n"));
        assertTrue(doc.contains("== Схема\n"), "отсылка к .mmd вместо ASCII-дерева");
        assertTrue(doc.contains("Adapter.mmd"));
        assertFalse(doc.contains("MQInput «Adapter Input» [ADAPTER.IN]"),
                "листинг «дерева-конспекта» удалён вместе с ASCII-форматом");
        assertTrue(doc.contains("== Узлы\n"));
        assertTrue(doc.contains("| Input | MQInput | Adapter Input | queueName = ADAPTER.IN"));
        assertTrue(doc.contains("| RouteByStatus | Default | DefaultQueue | in"));
        assertTrue(doc.contains("== Связи\n"));
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
        String doc = renderer.render(adapterLikeFlow());
        assertTrue(doc.contains("| RouteByStatus | Route | Route by status | —"));
    }

    @Test
    void outputIsDeterministic() {
        assertEquals(renderer.render(adapterLikeFlow()), renderer.render(adapterLikeFlow()));
    }
}
