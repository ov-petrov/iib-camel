package ru.petrovov.iib_camel.runtime_lite.transport;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.petrovov.iib_camel.runtime_lite.RuntimeLiteApplication;
import ru.petrovov.iib_camel.runtime_lite.jdbc.DeliveryTables;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.MessageBody;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.outbox.MessagePublisher;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxStatus;
import ru.petrovov.iib_camel.runtime_lite.processor.MessageProcessor;
import ru.petrovov.iib_camel.runtime_lite.xml.XmlService;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = RuntimeLiteApplication.class)
@ActiveProfiles("int-test")
@Import(BrokerPipelineIntegrationTest.Fixtures.class)
@EnabledIfSystemProperty(named = "runtime-lite.integration-test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrokerPipelineIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "");
    private static final String SCHEMA = "lite_broker_" + SUFFIX;
    private static final String INPUT_QUEUE = "LITE.E2E.DIRECT.IN." + SUFFIX;
    private static final String OUTPUT_QUEUE = "LITE.E2E.DIRECT.OUT." + SUFFIX;
    private static final String PROCESSOR = "brokerTestProcessor";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryTables tables;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private JmsListenerEndpointRegistry listeners;

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledTasks;

    @Autowired
    private BrokerTestProcessor processor;

    @Autowired
    private JmsMessageReader reader;

    @Autowired
    private XmlService xml;

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) {
        registry.add("spring.liquibase.default-schema", () -> SCHEMA);
        registry.add("runtime-lite.routes.broker.input-queue", () -> INPUT_QUEUE);
        registry.add("runtime-lite.routes.broker.processor", () -> PROCESSOR);
        registry.add("runtime-lite.soap.plain-jms-destinations", () -> String.join(",", INPUT_QUEUE, OUTPUT_QUEUE));
    }

    @Test
    void redeliversFailedInputAndPublishesOnlyCommittedOutputs() throws Exception {
        try (var context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE);
             var mqOutput = context.createConsumer(context.createQueue(OUTPUT_QUEUE))) {
            listeners.start();
            String inputBody = "<XmlValue><value>Привет</value></XmlValue>";
            var input = context.createTextMessage(inputBody);
            input.setStringProperty("sample_header", "sample_value");
            input.setJMSCorrelationID("synthetic-correlation");
            context.createProducer().send(context.createQueue(INPUT_QUEUE), input);

            try {
                assertThat(processor.redeliveryStarted.await(30, TimeUnit.SECONDS)).isTrue();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.inboundMessages(), Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.outbox(), Integer.class)).isZero();
                assertThat(mqOutput.receiveNoWait()).isNull();
            } finally {
                processor.allowRedelivery.countDown();
            }

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(processor.invocations).hasValue(2);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.inboundMessages(), Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForList("SELECT status FROM " + tables.outbox(), String.class))
                    .hasSize(2).containsOnly(OutboxStatus.SENT.name());
            });
            assertThat(jdbc.queryForMap("""
                SELECT message_id, source_destination, processor_id, body, body_xml::text AS body_xml,
                    headers ->> 'sample_header' AS sample_header
                FROM %s WHERE route_id = ?
                """.formatted(tables.inboundMessages()), "broker"))
                .containsEntry("message_id", input.getJMSMessageID())
                .containsEntry("source_destination", INPUT_QUEUE)
                .containsEntry("processor_id", PROCESSOR)
                .containsEntry("body", null)
                .containsEntry("body_xml", inputBody)
                .containsEntry("sample_header", "sample_value");

            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.enrichment(), Integer.class)).isZero();

            var first = mqOutput.receive(10000);
            var second = mqOutput.receive(10000);
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            var delivered = List.of(reader.read(first), reader.read(second));
            assertThat(delivered).allSatisfy(message -> {
                assertThat(xml.read(message.body(), XmlValue.class)).isEqualTo(new XmlValue("ПРИВЕТ"));
                assertThat(message.headers()).containsEntry("sample_header", "sample_value")
                    .containsEntry(MessageHeaders.CORRELATION_ID, "synthetic-correlation");
            });
            assertThat(delivered).extracting(message -> message.headers().get(MessageHeaders.MESSAGE_ID))
                .containsExactlyInAnyOrderElementsOf(jdbc.queryForList("SELECT id::text FROM " + tables.outbox(),
                    String.class));
            assertThat(mqOutput.receive(300)).isNull();
        }
    }

    @AfterAll
    void cleanup() {
        listeners.stop();
        scheduledTasks.destroy();
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean(PROCESSOR)
        BrokerTestProcessor brokerTestProcessor(MessagePublisher publisher, XmlService xml) {
            return new BrokerTestProcessor(publisher, xml);
        }
    }

    @RequiredArgsConstructor
    static class BrokerTestProcessor implements MessageProcessor {

        private final MessagePublisher publisher;
        private final XmlService xml;
        private final AtomicInteger invocations = new AtomicInteger();
        private final CountDownLatch redeliveryStarted = new CountDownLatch(1);
        private final CountDownLatch allowRedelivery = new CountDownLatch(1);

        @Override
        public OutboundMessage process(InboundMessage message) {
            var input = xml.read(message.body(), XmlValue.class);
            var result = new XmlValue(input.getValue().toUpperCase(Locale.ROOT));
            var body = MessageBody.xml(xml.writeDocument(result));
            var output = OutboundMessage.builder()
                .transport(Transport.JMS)
                .destination(OUTPUT_QUEUE)
                .payload(body)
                .headers(message.headers())
                .build();
            publisher.send(output);
            if (invocations.incrementAndGet() == 1) {
                throw new IllegalStateException("Synthetic transient processing failure");
            }
            redeliveryStarted.countDown();
            try {
                if (!allowRedelivery.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test redelivery was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Test redelivery interrupted", exception);
            }
            return output;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlRootElement(name = "XmlValue")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class XmlValue {

        private String value;
    }
}
