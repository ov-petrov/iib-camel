package ru.petrovov.iib_camel.runtime_lite.transport;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
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
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.petrovov.iib_camel.runtime_lite.RuntimeLiteApplication;
import ru.petrovov.iib_camel.runtime_lite.config.RoutingProperties;
import ru.petrovov.iib_camel.runtime_lite.jdbc.DeliveryTables;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.outbox.MessagePublisher;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxStatus;
import ru.petrovov.iib_camel.runtime_lite.processor.MessageProcessor;
import tools.jackson.dataformat.xml.XmlMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = RuntimeLiteApplication.class)
@ActiveProfiles({"test", "int-test"})
@Import(BrokerPipelineIntegrationTest.Fixtures.class)
@EnabledIfSystemProperty(named = "runtime-lite.integration-test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrokerPipelineIntegrationTest {

    private static final String SCHEMA = "lite_broker_" + UUID.randomUUID().toString().replace("-", "");
    private static final String TOPIC = "lite-broker-" + UUID.randomUUID();
    private static final String OUTPUT_QUEUE = System.getenv().getOrDefault("IIB_LITE_INT_OUTPUT_QUEUE", "LITE.TEST.OUT");
    private static final String GROUP = "lite-test-" + UUID.randomUUID();

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
    private XmlMapper xmlMapper;

    @Autowired
    private RoutingProperties routing;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    private boolean topicCreated;

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) {
        registry.add("spring.liquibase.default-schema", () -> SCHEMA);
        registry.add("spring.kafka.consumer.group-id", () -> GROUP);
    }

    @Test
    void redeliversFailedInputAndPublishesOnlyCommittedOutputs() throws Exception {
        String inputQueue = routing.getRoutes().get("broker").getInputQueue();
        String outputQueue = OUTPUT_QUEUE;
        if (inputQueue.equals(outputQueue)) {
            throw new IllegalArgumentException("The test requires separate input and output queues");
        }
        try (var context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE);
             var inputBrowser = context.createBrowser(context.createQueue(inputQueue));
             var outputBrowser = context.createBrowser(context.createQueue(outputQueue))) {
            if (inputBrowser.getEnumeration().hasMoreElements() || outputBrowser.getEnumeration().hasMoreElements()) {
                throw new IllegalStateException("The test requires empty dedicated input and output queues");
            }
        }

        try (var admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
            topicCreated = true;
        }
        try (var consumer = new KafkaConsumer<String, String>(consumerFactory.getConfigurationProperties());
             var context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE);
             var mqOutput = context.createConsumer(context.createQueue(outputQueue))) {
            consumer.subscribe(List.of(TOPIC));
            listeners.start();
            var input = context.createTextMessage("<XmlValue><value>Привет</value></XmlValue>");
            input.setStringProperty("sample_header", "sample_value");
            input.setJMSCorrelationID("synthetic-correlation");
            context.createProducer().send(context.createQueue(inputQueue), input);

            try {
                assertThat(processor.redeliveryStarted.await(30, TimeUnit.SECONDS)).isTrue();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.receipts(), Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.outbox(), Integer.class)).isZero();
                assertThat(mqOutput.receiveNoWait()).isNull();
            } finally {
                processor.allowRedelivery.countDown();
            }

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(processor.invocations).hasValue(2);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.receipts(), Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForList("SELECT status FROM " + tables.outbox(), String.class))
                    .hasSize(2).containsOnly(OutboxStatus.SENT.name());
            });

            var delivered = mqOutput.receive(10000);
            assertThat(delivered).isNotNull();
            var mqMessage = reader.read(delivered);
            assertThat(xmlMapper.readValue(mqMessage.body(), XmlValue.class)).isEqualTo(new XmlValue("ПРИВЕТ"));
            assertThat(mqMessage.headers()).containsEntry("sample_header", "sample_value")
                .containsEntry(MessageHeaders.CORRELATION_ID, "synthetic-correlation");
            assertThat(mqMessage.headers().get(MessageHeaders.MESSAGE_ID)).isEqualTo(deliveryId(Transport.JMS));
            assertThat(mqOutput.receive(300)).isNull();

            var records = new ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>();
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                return !records.isEmpty();
            });
            assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.key()).isEqualTo("synthetic");
                assertThat(xmlMapper.readValue(record.value(), XmlValue.class)).isEqualTo(new XmlValue("ПРИВЕТ"));
                assertThat(new String(record.headers().lastHeader(MessageHeaders.MESSAGE_ID).value(), StandardCharsets.UTF_8))
                    .isEqualTo(deliveryId(Transport.KAFKA));
            });
            assertThat(jdbc.queryForList("SELECT status FROM " + tables.outbox(), String.class))
                .containsOnly(OutboxStatus.SENT.name());
        }
    }

    private String deliveryId(Transport transport) {
        return jdbc.queryForObject("SELECT id FROM " + tables.outbox() + " WHERE transport = ?",
            UUID.class, transport.name()).toString();
    }

    @AfterAll
    void cleanup() throws Exception {
        listeners.stop();
        scheduledTasks.destroy();
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        if (topicCreated) {
            try (var admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
                admin.deleteTopics(List.of(TOPIC)).all().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        BrokerTestProcessor brokerTestProcessor(MessagePublisher publisher, XmlMapper xmlMapper) {
            return new BrokerTestProcessor(publisher, xmlMapper, OUTPUT_QUEUE);
        }
    }

    @RequiredArgsConstructor
    static class BrokerTestProcessor implements MessageProcessor {

        private final MessagePublisher publisher;
        private final XmlMapper xmlMapper;
        private final String outputQueue;
        private final AtomicInteger invocations = new AtomicInteger();
        private final CountDownLatch redeliveryStarted = new CountDownLatch(1);
        private final CountDownLatch allowRedelivery = new CountDownLatch(1);

        @Override
        public OutboundMessage process(InboundMessage message) {
            var input = xmlMapper.readValue(message.body(), XmlValue.class);
            var result = new XmlValue(input.value().toUpperCase(Locale.ROOT));
            var body = xmlMapper.writeValueAsString(result);
            publisher.send(new OutboundMessage(Transport.JMS, outputQueue, body, "synthetic", message.headers()));
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
            return new OutboundMessage(Transport.KAFKA, TOPIC, body, "synthetic", message.headers());
        }
    }

    public record XmlValue(String value) {
    }
}
