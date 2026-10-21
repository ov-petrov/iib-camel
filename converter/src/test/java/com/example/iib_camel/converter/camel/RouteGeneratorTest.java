package com.example.iib_camel.converter.camel;

import com.example.iib_camel.converter.msgflow.MsgFlow;
import com.example.iib_camel.converter.msgflow.MsgFlowParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты генератора (L2): семантические проверки + golden-сравнение из
 * docs/test-strategy.ru.md (первое утверждение золотника — см. README fixture).
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
        String xml = new RouteGenerator(adapterFlow()).generate();
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
                "/corpus/corr-01-simple-adapter/expected/Adapter.route.xml")) {
            String expected = new String(golden.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(expected, new RouteGenerator(adapterFlow()).generate());
        }
    }
}
