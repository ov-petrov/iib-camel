package ru.petrovov.iib_camel.runtime_lite.processor.example;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxStatus;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = RuntimeLiteApplication.class)
@ActiveProfiles({"test", "local", "example-test"})
@EnabledIfSystemProperty(named = "runtime-lite.integration-test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExampleProcessorIntegrationTest {

    private static final String ID = UUID.randomUUID().toString();
    private static final String SCHEMA = "lite_example_" + ID.replace("-", "");
    private static final String MAIN_TOPIC = "lite-example-main-" + ID;
    private static final String MAIL_TOPIC = "lite-example-mail-" + ID;
    private static final String ERROR_TOPIC = "lite-example-error-" + ID;
    private static final String GROUP = "lite-example-group-" + ID;
    private static final List<String> TOPICS = List.of(MAIN_TOPIC, MAIL_TOPIC, ERROR_TOPIC);

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
    private RoutingProperties routing;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    private boolean topicsCreated;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.liquibase.default-schema", () -> SCHEMA);
        registry.add("spring.kafka.consumer.group-id", () -> GROUP);
        registry.add("runtime-lite.example.main-topic", () -> MAIN_TOPIC);
        registry.add("runtime-lite.example.mail-topic", () -> MAIL_TOPIC);
        registry.add("runtime-lite.example.error-topic", () -> ERROR_TOPIC);
    }

    @Test
    void routesValidAndInvalidPaymentsThroughRealBrokers() throws Exception {
        String inputQueue = routing.getRoutes().get("example").getInputQueue();
        try (var context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE);
             var browser = context.createBrowser(context.createQueue(inputQueue))) {
            if (browser.getEnumeration().hasMoreElements()) {
                throw new IllegalStateException("The example test requires an empty dedicated input queue");
            }
        }

        try (var admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            admin.createTopics(TOPICS.stream().map(topic -> new NewTopic(topic, 1, (short) 1)).toList())
                .all().get(15, TimeUnit.SECONDS);
            topicsCreated = true;
        }

        String withoutNotification = payment("document-one", "10.00", false);
        String withNotification = payment("document-two", "20.00", true);
        String invalidXml = "<PaymentDocument><documentId>broken</documentId>";
        String negativeAmount = payment("document-three", "-1.00", true);

        try (var consumer = new KafkaConsumer<String, String>(consumerFactory.getConfigurationProperties());
             var context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
            consumer.subscribe(TOPICS);
            var producer = context.createProducer();
            var queue = context.createQueue(inputQueue);
            var first = context.createTextMessage(withoutNotification);
            var second = context.createTextMessage(withNotification);
            var invalid = context.createTextMessage(invalidXml);
            var negative = context.createTextMessage(negativeAmount);
            producer.send(queue, first);
            producer.send(queue, second);
            producer.send(queue, invalid);
            producer.send(queue, negative);
            String invalidId = invalid.getJMSMessageID();
            String negativeId = negative.getJMSMessageID();
            assertThat(invalidId).isNotBlank();
            assertThat(negativeId).isNotBlank();
            listeners.start();

            var records = new ArrayList<ConsumerRecord<String, String>>();
            await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.receipts(), Integer.class)).isEqualTo(4);
                assertThat(jdbc.queryForList("SELECT status FROM " + tables.outbox(), String.class))
                    .hasSize(5).containsOnly(OutboxStatus.SENT.name());
                assertThat(records).hasSizeGreaterThanOrEqualTo(5);
            });
            consumer.poll(Duration.ofSeconds(1)).forEach(records::add);
            assertThat(records).hasSize(5);

            var main = records.stream().filter(record -> record.topic().equals(MAIN_TOPIC)).toList();
            assertThat(main).hasSize(2);
            assertThat(main).anySatisfy(record -> {
                assertThat(record.key()).isEqualTo("document-one");
                assertThat(record.value()).isEqualTo(withoutNotification);
            });
            assertThat(main).anySatisfy(record -> {
                assertThat(record.key()).isEqualTo("document-two");
                assertThat(record.value()).isEqualTo(withNotification);
            });
            assertThat(records.stream().filter(record -> record.topic().equals(MAIL_TOPIC)).toList())
                .singleElement().satisfies(record -> assertThat(record.value()).isNotBlank());
            var errors = records.stream().filter(record -> record.topic().equals(ERROR_TOPIC)).toList();
            assertThat(errors).hasSize(2);
            assertError(errors, invalidId, invalidXml, "INVALID_XML");
            assertError(errors, negativeId, negativeAmount, "NON_POSITIVE_AMOUNT");
        }
    }

    private static String payment(String id, String amount, boolean notify) {
        return """
            <PaymentDocument><documentId>%s</documentId><amount>%s</amount><currency>USD</currency><payer><name>Example</name><account>12345</account></payer><sendNotification>%s</sendNotification></PaymentDocument>
            """.formatted(id, amount, notify).strip();
    }

    private static void assertError(List<ConsumerRecord<String, String>> records, String key, String body, String code) {
        assertThat(records).anySatisfy(record -> {
            assertThat(record.key()).isEqualTo(key);
            assertThat(record.value()).isEqualTo(body);
            assertThat(record.headers().lastHeader("example_error_code")).isNotNull();
            assertThat(new String(record.headers().lastHeader("example_error_code").value(), StandardCharsets.UTF_8))
                .isEqualTo(code);
        });
    }

    @AfterAll
    void cleanup() throws Exception {
        listeners.stop();
        scheduledTasks.destroy();
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        if (topicsCreated) {
            try (var admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
                admin.deleteTopics(TOPICS).all().get(10, TimeUnit.SECONDS);
            }
        }
    }
}
