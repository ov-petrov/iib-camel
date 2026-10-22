package com.example.iib_camel.runtime.e2e;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.apache.camel.CamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;import org.springframework.beans.factory.annotation.Autowired;
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
 * E2E-тест уровня L3 (docs/test-strategy.ru.md): сгенерированные маршруты
 * конвертера (camel/*.xml) выполняются на встроенном Artemis, сценарии —
 * из декларативных датасетов e2e (параметризация по corpus-директориям).
 *
 * Каждый датасет: список очередей + записи «вход (очередь, сообщение, MQMD-приоритет) →
 * ожидание (очередь, приоритет, MQMD-свойство)». Проверяем: маршрутизацию (Route-узел,
 * подпоток direct:), перенос тела (SET OutputRoot = InputRoot), QoS (preserveMessageQos),
 * MQMD-метки сгенерированных Compute-бинов и переход в ERROR.Q при некорректном XML.
 * corr-01 — 7-й узел Adapter_Compute1 (ручной бин), corr-02 — сгенерированный (FR-3.2).
 */
@SpringBootTest
@Timeout(120)
class E2eDatasetTest {

    private static final List<String> E2E_DIRS =
            List.of("e2e/corr-01-simple-adapter", "e2e/corr-02-subflows");

    @Autowired
    private CamelContext camelContext;

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @BeforeEach
    void contextIsRunningAndQueuesAreEmpty() {
        assertNotNull(camelContext.getRoute("Adapter"), "маршрут Adapter должен быть загружен (FR-R2.1)");
        assertNotNull(camelContext.getRoute("Order"), "маршрут Order должен быть загружен (FR-R2.1)");
        assertNotNull(camelContext.getRoute("Check"), "вложенный маршрут подпотока Check (direct:, FR-2.2)");
        for (String dir : E2E_DIRS) {
            for (String queue : queuesOf(dir)) {
                drain(queue);
            }
        }
    }

    /** Читает общий список очередей из датасета — каталог корректируется в yml, не в код. */
    @SuppressWarnings("unchecked")
    private List<String> queuesOf(String dir) {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/" + dir + "/dataset.yml"))) {
            return (List<String>) ((Map<String, Object>) new Yaml().load(in)).get("queues");
        } catch (Exception e) {
            throw new IllegalStateException(dir + ": " + e.getMessage(), e);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void allDatasetCasesPass() throws Exception {
        int passed = 0;
        for (String dir : E2E_DIRS) {
            Map<String, Object> dataset;
            try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/" + dir + "/dataset.yml"))) {
                dataset = (Map<String, Object>) new Yaml().load(in);
            }
            List<Map<String, Object>> cases = (List<Map<String, Object>>) dataset.get("cases");
            for (Map<String, Object> aCase : cases) {
                runCase(dir, aCase);
                passed++;
            }
        }
        assertEquals(5, passed, "все сценарии обоих датасетов пройдены");
    }

    /** Исполняет один сценарий датасета: отправка → получение из ожидаемой очереди → проверки. */
    @SuppressWarnings("unchecked")
    private void runCase(String dir, Map<String, Object> aCase) throws Exception {
        String name = (String) aCase.get("name");
        Map<String, Object> input = (Map<String, Object>) aCase.get("input");
        Map<String, Object> inputMqmd = (Map<String, Object>) input.get("mqmd");
        Map<String, Object> expect = (Map<String, Object>) aCase.get("expect");
        Map<String, Object> expectMqmd = (Map<String, Object>) expect.get("mqmd");

        String body = readMessage(dir, (String) input.get("message"));
        String inputQueue = (String) input.get("queue");
        Integer inputPriority = inputMqmd == null ? null : (Integer) inputMqmd.get("priority");

        // вход: JmsTemplate задаёт приоритет (аналог MQMD) на JMS-сообщении;
        // приоритет JMS применяется только при explicit QoS (Spring JmsTemplate)
        var inputTemplate = new JmsTemplate(connectionFactory);
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
        String actualBody = ((TextMessage) received).getText().strip();
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
            // общие MQMD-свойства (MQMD.CommentField и любые другие поля сгенерированных бинов)
            Map<String, Object> properties = (Map<String, Object>) expectMqmd.get("properties");
            if (properties != null) {
                for (Map.Entry<String, Object> property : properties.entrySet()) {
                    assertEquals(property.getValue(), received.getStringProperty(property.getKey()),
                            name + ": MQMD-свойство " + property.getKey() + " (сгенерированный бин)");
                }
            }
            // приложение может задавать и прямые имена полей (commentField → CommentField)
            Object comment = expectMqmd.get("commentField");
            if (comment != null) {
                assertEquals(comment, received.getStringProperty("CommentField"),
                        name + ": MQMD.CommentField отсутствует");
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

    private String readMessage(String dir, String resource) throws Exception {
        try (InputStream in = Objects.requireNonNull(
                getClass().getResourceAsStream("/" + dir + "/" + resource))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    private String stripXmlDecl(String xml) {
        int idx = xml.indexOf("<?xml");
        return idx < 0 ? xml : xml.substring(idx).substring(xml.indexOf("?>") + 2).strip();
    }
}
