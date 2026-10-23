package ru.petrovov.iib_camel.runtime.app;

import jakarta.jms.DeliveryMode;
import jakarta.jms.Session;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.camel.CamelContext;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.petrovov.iib_camel.runtime.delivery.outbox.OutboxDispatcher;
import ru.petrovov.iib_camel.runtime.delivery.outbox.OutboxStatus;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "IIB_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "IIB_TEST_ARTEMIS_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "IIB_TEST_KAFKA_BOOTSTRAP_SERVERS", matches = ".+")
@SpringBootTest(classes = RuntimeApplication.class, properties = {
        "spring.main.web-application-type=none", "camel.main.run-controller=false",
        "iib.delivery.enabled=true", "iib.delivery.publisher-enabled=false",
        "iib.modules.example.enabled=true", "iib.transports.artemis.enabled=true",
        "iib.transports.kafka.enabled=true", "iib.transports.ibmmq.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BrokerPipelineIntegrationTest {
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "");
    private static final String SCHEMA = "runtime_pipeline_test_" + SUFFIX;
    private static final String INPUT_QUEUE = "synthetic.runtime.pipeline.in." + SUFFIX;
    private static final String OUTPUT_TOPIC = "synthetic.runtime.pipeline.out." + SUFFIX;
    private static final String SOURCE = "synthetic-pipeline-source-" + SUFFIX;
    private static boolean topicCreated;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    CamelContext camel;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("IIB_TEST_POSTGRES_URL"));
        properties.add("spring.datasource.username", BrokerPipelineIntegrationTest::postgresUser);
        properties.add("spring.datasource.password", BrokerPipelineIntegrationTest::postgresPassword);
        properties.add("iib.delivery.schema", () -> SCHEMA);
        properties.add("iib.delivery.create-schema", () -> "true");
        properties.add("spring.liquibase.default-schema", () -> SCHEMA);
        properties.add("spring.liquibase.liquibase-schema", () -> SCHEMA);
        properties.add("iib.transports.artemis.broker-url", () -> System.getenv("IIB_TEST_ARTEMIS_URL"));
        optional(properties, "iib.transports.artemis.username", "IIB_TEST_ARTEMIS_USERNAME");
        optional(properties, "iib.transports.artemis.password", "IIB_TEST_ARTEMIS_PASSWORD");
        properties.add("iib.transports.kafka.bootstrap-servers", BrokerPipelineIntegrationTest::kafkaBrokers);
        properties.add("iib.modules.example.input-uri", () -> "artemis:queue:" + INPUT_QUEUE);
        properties.add("iib.modules.example.source-id", () -> SOURCE);
        properties.add("iib.modules.example.output-transport", () -> "kafka");
        properties.add("iib.modules.example.output-destination", () -> OUTPUT_TOPIC);
    }

    @Test
    void actualXmlModuleCommitsIncomingMessageAndPublishesConfirmedKafkaEvent() throws Exception {
        assertThat(camel.getRoute("example.receive")).isNotNull();
        assertThat(camel.getRouteController().getRouteStatus("example.receive").isStarted()).isTrue();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafkaBrokers()))) {
            admin.createTopics(List.of(new NewTopic(OUTPUT_TOPIC, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            topicCreated = true;
        }
        byte[] payload = {0, 1, (byte) 255, 2, 0};
        String inputId = UUID.randomUUID().toString();
        sendIndependentJmsMessage(inputId, payload);
        awaitReadyEvent();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + inboxTable(), Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT message_id FROM " + inboxTable(), String.class)).isEqualTo(inputId);
        assertThat(jdbc.queryForObject("SELECT source FROM " + inboxTable(), String.class)).isEqualTo(SOURCE);
        var stored = jdbc.queryForObject("SELECT id, payload, partition_key FROM " + outboxTable() + " WHERE status = ?",
                (result, row) -> new StoredEvent(result.getObject("id", UUID.class), result.getBytes("payload"),
                    result.getString("partition_key")), OutboxStatus.READY.getDatabaseValue());
        assertThat(stored).isNotNull();
        assertThat(stored.payload()).containsExactly(payload);
        assertThat(stored.key()).isEqualTo(inputId);

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM " + outboxTable() + " WHERE id = ?", String.class, stored.id()))
            .isEqualTo(OutboxStatus.PROCESSED.getDatabaseValue());
        assertThat(jdbc.queryForObject(
            "SELECT processed_at IS NOT NULL FROM " + outboxTable() + " WHERE id = ?", Boolean.class, stored.id()))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + outboxTable() + " WHERE id = ?", Integer.class, stored.id()))
                .isEqualTo(1);

        var received = receiveIndependentKafkaEvent();
        assertThat(received.value()).containsExactly(payload);
        assertThat(new String(received.key(), StandardCharsets.UTF_8)).isEqualTo(inputId);
        assertThat(received.headers().lastHeader("applicationEventId")).isNotNull();
        assertThat(new String(received.headers().lastHeader("applicationEventId").value(), StandardCharsets.UTF_8))
                .isEqualTo(stored.id().toString());
    }

    private static void sendIndependentJmsMessage(String identity, byte[] payload) throws Exception {
        try (var factory = new ActiveMQConnectionFactory(
            System.getenv("IIB_TEST_ARTEMIS_URL"))) {
            String username = System.getenv("IIB_TEST_ARTEMIS_USERNAME");
            String password = System.getenv("IIB_TEST_ARTEMIS_PASSWORD");
            if (username != null) {
                factory.setUser(username);
            }
            if (password != null) {
                factory.setPassword(password);
            }
            try (var connection = factory.createConnection();
                 var session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                 var producer = session.createProducer(session.createQueue(INPUT_QUEUE))) {
                var message = session.createBytesMessage();
                message.writeBytes(payload);
                message.setStringProperty("applicationEventId", identity);
                producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                producer.send(message);
            }
        }
    }

    private void awaitReadyEvent() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer ready = jdbc.queryForObject("SELECT count(*) FROM " + outboxTable() + " WHERE status = ?",
                Integer.class, OutboxStatus.READY.getDatabaseValue());
            if (ready != null && ready == 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("XML input route did not commit a READY event within 10 seconds");
    }

    private static ConsumerRecord<byte[], byte[]> receiveIndependentKafkaEvent() {
        var settings = Map.<String, Object>of("bootstrap.servers", kafkaBrokers(),
                "group.id", "synthetic-pipeline-consumer-" + SUFFIX,
                "enable.auto.commit", false, "auto.offset.reset", "earliest");
        try (var consumer = new KafkaConsumer<byte[], byte[]>(
            settings, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            var partition = new TopicPartition(OUTPUT_TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            var records = new ArrayList<ConsumerRecord<byte[], byte[]>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (records.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(100)).forEach(records::add);
            }
            assertThat(records).hasSize(1);
            return records.getFirst();
        }
    }

    @AfterAll
    static void removeOwnedResources() throws Exception {
        try {
            if (topicCreated) {
                try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafkaBrokers()))) {
                    admin.deleteTopics(List.of(OUTPUT_TOPIC)).all().get(10, TimeUnit.SECONDS);
                }
            }
        } finally {
            var dataSource = new DriverManagerDataSource(
                System.getenv("IIB_TEST_POSTGRES_URL"), postgresUser(), postgresPassword());
            new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private static void optional(DynamicPropertyRegistry properties, String property, String environment) {
        String value = System.getenv(environment);
        if (value != null) {
            properties.add(property, () -> value);
        }
    }

    private static String postgresUser() {
        return System.getenv().getOrDefault("IIB_TEST_POSTGRES_USER", "postgres");
    }

    private static String postgresPassword() {
        return System.getenv().getOrDefault("IIB_TEST_POSTGRES_PASSWORD", "");
    }

    private static String kafkaBrokers() {
        return System.getenv("IIB_TEST_KAFKA_BOOTSTRAP_SERVERS");
    }

    private static String inboxTable() {
        return "\"" + SCHEMA + "\".\"runtime_inbox\"";
    }

    private static String outboxTable() {
        return "\"" + SCHEMA + "\".\"runtime_outbox\"";
    }

    private record StoredEvent(UUID id, byte[] payload, String key) {}
}
