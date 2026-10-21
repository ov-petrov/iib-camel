package com.example.iib_camel.runtime.e2e;

import jakarta.jms.Message;
import org.apache.camel.CamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jms.core.JmsTemplate;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-тест уровня L3 (docs/test-strategy.ru.md): сгенерированный маршрут
 * конвертера (camel/Adapter-route.xml) выполняется на встроенном Artemis,
 * сценарии берутся из декларативного датасета e2e (dataset.yml).
 *
 * Каждая запись датасета: вход (очередь, сообщение, MQMD-приоритет) →
 * ожидание (очередь, приоритет, ApplicationIdData). Проверяем:
 * маршрутизацию по статусу, перенос тела (SET OutputRoot = InputRoot),
 * метку миграции IIB-CAMEL-MIG и переход в ERROR.Q при некорректном XML.
 */
@SpringBootTest
@Timeout(120)
class E2eDatasetTest {

    private static final String E2E_DIR = "e2e/corr-01-simple-adapter";
    private static final List<String> QUEUE_NAMES =
            List.of("ADAPTER.IN", "ADAPTER.PRIORITY.Q", "ADAPTER.DEFAULT.Q", "ADAPTER.ERROR.Q");

    @Autowired
    private CamelContext camelContext;

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private jakarta.jms.ConnectionFactory connectionFactory;

    @BeforeEach
    void contextIsRunningAndQueuesAreEmpty() {
        assertNotNull(camelContext.getRoute("Adapter"), "маршрут Adapter должен быть загружен (FR-R2.1)");
        for (String queue : QUEUE_NAMES) {
            drain(queue);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void datasetCasesPass() throws Exception {
        Map<String, Object> dataset;
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/" + E2E_DIR + "/dataset.yml"))) {
            dataset = (Map<String, Object>) new Yaml().load(in);
        }
        List<Map<String, Object>> cases = (List<Map<String, Object>>) dataset.get("cases");
        assertTrue(cases.size() >= 3, "в датасете должны быть все сценарии corr-01");

        int passed = 0;
        for (Map<String, Object> aCase : cases) {
            runCase(aCase);
            passed++;
        }
        assertEquals(cases.size(), passed);
    }

    /** Исполняет один сценарий датасета: отправка → получение из ожидаемой очереди → проверки. */
    private void runCase(Map<String, Object> aCase) throws Exception {
        String name = (String) aCase.get("name");
        Map<String, Object> input = (Map<String, Object>) aCase.get("input");
        Map<String, Object> inputMqmd = (Map<String, Object>) input.get("mqmd");
        Map<String, Object> expect = (Map<String, Object>) aCase.get("expect");
        Map<String, Object> expectMqmd = (Map<String, Object>) expect.get("mqmd");

        String body = readMessage((String) input.get("message"));
        String inputQueue = (String) input.get("queue");
        Integer inputPriority = inputMqmd == null ? null : (Integer) inputMqmd.get("priority");

        // вход: JmsTemplate задаёт приоритет (аналог MQMD) на JMS-сообщении;
        // приоритет JMS применяется только при explicit QoS (Spring JmsTemplate)
        var inputTemplate = new org.springframework.jms.core.JmsTemplate(connectionFactory);
        inputTemplate.setExplicitQosEnabled(true);
        if (inputPriority != null) {
            inputTemplate.setPriority(inputPriority);
        }
        inputTemplate.convertAndSend(inputQueue, body);

        // ожидаемая очередь (FR-R2.1): сообщение должно появиться именно в ней
        String expectedQueue = (String) expect.get("queue");
        jmsTemplate.setReceiveTimeout(10_000);
        Message received = jmsTemplate.receive(expectedQueue);
        assertNotNull(received, name + ": нет сообщения в очереди " + expectedQueue);

        // тело переносится без изменений (Compute: SET OutputRoot = InputRoot);
        // форматируем канонически — Arts различия в пробелах не важны
        String expectedBody = stripXmlDecl(body);
        String actualBody = ((jakarta.jms.TextMessage) received).getText().strip();
        assertEquals(expectedBody.replaceAll("\\s+", ""), actualBody.replaceAll("\\s+", ""),
                name + ": тело сообщения изменилось в маршруте");

        // QoS-картирование MQMD → JMS
        if (expectMqmd != null) {
            Integer expectedPriority = (Integer) expectMqmd.get("priority");
            if (expectedPriority != null) {
                assertEquals(expectedPriority, received.getJMSPriority(),
                        name + ": приоритет (аналог MQMD Priority) не перенесён");
            }
            Object appId = expectMqmd.get("applicationIdData");
            if (appId != null) {
                assertEquals(appId, received.getStringProperty("ApplicationIdData"),
                        name + ": метка миграции (MQMD.ApplicationIdData) отсутствует");
            }
        }
    }

    /** Пустые очереди перед сценарием — изоляция прогонов. */
    private void drain(String queue) {
        jmsTemplate.setReceiveTimeout(150);
        try {
            while (jmsTemplate.receive(queue) != null) {
                // опустошаем
            }
        } finally {
            jmsTemplate.setReceiveTimeout(10_000);
        }
    }

    private String readMessage(String resource) throws Exception {
        try (InputStream in = Objects.requireNonNull(
                getClass().getResourceAsStream("/" + E2E_DIR + "/" + resource))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    private String stripXmlDecl(String xml) {
        int idx = xml.indexOf("<?xml");
        return idx < 0 ? xml : xml.substring(idx).substring(xml.indexOf("?>") + 2).strip();
    }
}
