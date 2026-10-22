package com.example.iib_camel.converter.camel;

import com.example.iib_camel.converter.msgflow.FlowConnection;
import com.example.iib_camel.converter.msgflow.FlowNode;
import com.example.iib_camel.converter.msgflow.MsgFlow;
import com.example.iib_camel.converter.msgflow.MsgFlowParser;
import com.example.iib_camel.converter.report.UnitStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты генератора (L2): семантические проверки + golden-сравнение из
 * docs/test-strategy.ru.md, а также диагностичка единиц конвертации (FR-5.2):
 * нехватка свойств → «частично», неизвестный тип → «не поддерживается»,
 * узлы вне пути от MQInput не молчат.
 */
class RouteGeneratorTest {

    private MsgFlow adapterFlow() {
        try (var in = getClass().getResourceAsStream("/corpus/corr-01-simple-adapter/Adapter.msgflow")) {
            return new MsgFlowParser().parse(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void generatesRouteSkeleton() {
        String xml = new RouteGenerator(adapterFlow()).generate().xml();
        assertTrue(xml.contains("<from uri=\"jms:ADAPTER.IN\"/>"));
        assertTrue(xml.contains("<to uri=\"bean:adapterCompute1\"/>"));
        assertTrue(xml.contains("<simple>${body.order.status} == 'HIGH'</simple>"));
        assertTrue(xml.contains("<to uri=\"jms:ADAPTER.PRIORITY.Q\"/>"));
        assertTrue(xml.contains("<to uri=\"jms:ADAPTER.DEFAULT.Q\"/>"));
        assertTrue(xml.contains("<to uri=\"jms:ADAPTER.ERROR.Q\"/>"));
        assertTrue(xml.contains("<doCatch>"));
        assertTrue(xml.contains("<otherwise>"));
        // трассируемость: каждый шаг помечен исходным узлом (FR-2.6)
        assertTrue(xml.contains("source: узел RouteByStatus"));
    }

    @Test
    void matchesGoldenFile() throws Exception {
        try (var golden = getClass().getResourceAsStream(
                "/corpus/corr-01-simple-adapter/expected/Adapter-route.xml")) {
            String expected = new String(golden.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(expected, new RouteGenerator(adapterFlow()).generate().xml());
        }
    }

    @Test
    void corpusFixtureIsFullyConverted() {
        var result = new RouteGenerator(adapterFlow()).generate();
        for (String nodeId : List.of("Input", "Enrich", "RouteByStatus",
                "PriorityQueue", "DefaultQueue", "ErrorQueue")) {
            assertEquals(UnitStatus.CONVERTED, result.statusOf(nodeId),
                    "узел " + nodeId + " должен быть конвертирован полностью");
        }
    }

    @Test
    void missingPropertiesAndUnknownKindAreDiagnosed() {
        // In — без queueName; Comp → Out (MQOutput без queueName); Trace1 — вне пути
        var flow = new MsgFlow("Broken.msgflow",
                List.of(
                        new FlowNode("In", "MQInput", "", Map.of(), List.of()),
                        new FlowNode("Comp", "Compute", "",
                                Map.of("computeExpression", "esql://routine/#Mod.Main"), List.of()),
                        new FlowNode("Out", "MQOutput", "", Map.of(), List.of()),
                        new FlowNode("Trace1", "Trace", "", Map.of(), List.of())),
                List.of(
                        new FlowConnection("In", "out", "Comp", "in"),
                        new FlowConnection("Comp", "out", "Out", "in")));
        var result = new RouteGenerator(flow).generate();

        assertEquals(UnitStatus.PARTIAL, result.statusOf("In"), "нет queueName → частично");
        assertEquals(UnitStatus.CONVERTED, result.statusOf("Comp"));
        assertEquals(UnitStatus.PARTIAL, result.statusOf("Out"), "нет queueName → частично");
        assertEquals(UnitStatus.NOT_SUPPORTED, result.statusOf("Trace1"),
                "узел вне пути и вне SUPPORTED_BASE_KINDS → не поддерживается");
        assertTrue(result.xml().contains("TODO-queue-not-set"),
                "заглушка в from при отсутствии queueName");
        assertTrue(result.diagnoses().stream()
                        .filter(d -> d.nodeId().equals("Trace1"))
                        .allMatch(d -> d.note().contains("нет пути от входного узла")),
                "узел вне пути не должен молчать: примечание содержит причину");
    }

    @Test
    void flowWithoutInputProducesNoRoutesAndDiagnosesAll() {
        var flow = new MsgFlow("NoInput.msgflow",
                List.of(new FlowNode("Out", "MQOutput", "", Map.of("queueName", "Q1"), List.of())),
                List.of());
        var result = new RouteGenerator(flow).generate();

        assertTrue(!result.xml().contains("<route "),
                "без входного узла маршрутов нет");
        assertEquals(UnitStatus.PARTIAL, result.statusOf("Out"));
        assertTrue(result.diagnoses().stream()
                        .filter(d -> d.nodeId().equals("Out"))
                        .allMatch(d -> d.note().contains("нет входного узла MQInput")),
                "примечание объясняет отсутствие маршрута");
    }
}
