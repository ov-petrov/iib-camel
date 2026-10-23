package ru.petrovov.iib_camel.converter.camel;

import org.junit.jupiter.api.Test;
import ru.petrovov.iib_camel.converter.msgflow.FlowConnection;
import ru.petrovov.iib_camel.converter.msgflow.FlowNode;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlow;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlowParser;
import ru.petrovov.iib_camel.converter.report.UnitStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RouteGeneratorTest {

    private MsgFlow adapterFlow() {
        try (var in = getClass().getResourceAsStream("/corpus/corr-01-simple-adapter/Adapter.msgflow")) {
            return new MsgFlowParser().parse(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RouteGenerator orderGenerator() {
        var parser = new MsgFlowParser();
        MsgFlow order = load("/corpus/corr-02-subflows/Order.msgflow");
        MsgFlow check = load("/corpus/corr-02-subflows/Check.subflow");
        return new RouteGenerator(order, Map.of("Check", check, "Check.subflow", check));
    }

    private static MsgFlow load(String resource) {
        try (var in = RouteGeneratorTest.class.getResourceAsStream(resource)) {
            return new MsgFlowParser().parse(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void generatesRouteSkeleton() {
        String xml = new RouteGenerator(adapterFlow()).generate().xml();
        assertTrue(xml.contains("<from uri=\"jms:ADAPTER.IN?transacted=true&amp;disableReplyTo=true\"/>"));
        assertTrue(xml.contains("<to uri=\"bean:adapterCompute1\"/>"));
        assertTrue(xml.contains("<xpath>/Adapter/Order/status=&apos;HIGH&apos;</xpath>"));
        assertTrue(xml.contains("<to uri=\"jms:ADAPTER.PRIORITY.Q\"/>"));
        assertTrue(xml.contains("<to uri=\"jms:ADAPTER.DEFAULT.Q\"/>"));
        assertTrue(xml.contains("<to uri=\"jms:ADAPTER.ERROR.Q\"/>"));
        assertTrue(xml.contains("<doCatch>"));
        assertTrue(xml.contains("<otherwise>"));
        assertFalse(xml.contains("<!--"));
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

    @Test
    void corr02MatchesGoldenFile() throws Exception {
        try (var golden = getClass().getResourceAsStream(
                "/corpus/corr-02-subflows/expected/Order-route.xml")) {
            String expected = new String(golden.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(expected, orderGenerator().generate().xml());
        }
    }

    @Test
    void subflowReferenceIsInlinedAsDirectRoute() {
        var result = orderGenerator().generate();

        assertEquals(UnitStatus.CONVERTED, result.statusOf("CheckRef"),
                "подпоток с найденным reference-файлом → конвертировано");
        assertTrue(result.xml().contains("<to uri=\"direct:Check\"/>"),
                "вызов подпотока — direct: (FR-2.2)");
        assertTrue(result.xml().contains("<route id=\"Check\">"));
        assertTrue(result.xml().contains("<from uri=\"direct:Check\"/>"));
        assertTrue(result.xml().contains("<to uri=\"bean:checkCompute1\"/>"),
                "содержимое подпотока развёрнуто во вложенный маршрут");
        assertTrue(result.xml().contains("Unsupported IIB Trace"),
                "неподдерживаемый Trace останавливает маршрут");
        assertTrue(result.xml().contains("<to uri=\"jms:ORDERS.OUT\"/>"));
        assertEquals(UnitStatus.CONVERTED, result.statusOf("Acked"));
    }

    @Test
    void unresolvedSubflowReferenceBecomesStub() {
        var parser = new MsgFlowParser();
        MsgFlow order = load("/corpus/corr-02-subflows/Order.msgflow");
        var result = new RouteGenerator(order, Map.of()).generate();

        assertEquals(UnitStatus.NOT_SUPPORTED, result.statusOf("CheckRef"),
                "подпоток не найден → честный статус «не поддерживается»");
        assertTrue(result.diagnoses().stream()
                        .filter(d -> d.nodeId().equals("CheckRef"))
                        .allMatch(d -> d.note().contains("не найден во входном наборе")),
                "примечание называет причину: reference-файл отсутствует");
        assertTrue(result.xml().contains("Unsupported IIB Subflow"),
                "неразрешённая ссылка останавливает маршрут");
    }

    @Test
    void inlineResultDiagnosesSubflowUnitsHonesty() {
        var parser = new MsgFlowParser();
        MsgFlow check = load("/corpus/corr-02-subflows/Check.subflow");
        MsgFlow order = load("/corpus/corr-02-subflows/Order.msgflow");
        var registry = Map.of("Check", check, "Check.subflow", check);
        var result = new RouteGenerator(check, registry).inlineResult();

        assertEquals(UnitStatus.CONVERTED, result.statusOf("SourceFlow"),
                "вход подпотока (eflow:FCMSource) → конвертировано");
        assertEquals(UnitStatus.CONVERTED, result.statusOf("CheckStep"));
        assertEquals(UnitStatus.NOT_SUPPORTED, result.statusOf("TraceNode"),
                "Trace-узел внутри подпотока честно помечен (FR-2.7)");
        assertEquals(UnitStatus.CONVERTED, result.statusOf("SinkFlow"),
                "выход подпотока (eflow:FCMSink) → конвертировано");
    }
}
