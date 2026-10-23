package ru.petrovov.iib_camel.runtime.app;

import org.apache.camel.CamelContext;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import ru.petrovov.iib_camel.runtime.delivery.handler.MessageHandler;
import ru.petrovov.iib_camel.runtime.delivery.inbox.InboxService;
import ru.petrovov.iib_camel.runtime.delivery.outbox.OutboxStatus;
import ru.petrovov.iib_camel.runtime.message.InboundMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@EnabledIfEnvironmentVariable(named = "IIB_TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "IIB_TEST_KAFKA_BOOTSTRAP_SERVERS", matches = ".+")
@SpringBootTest(classes = RuntimeApplication.class, properties = {
        "spring.main.web-application-type=none", "camel.main.run-controller=false",
        "iib.delivery.enabled=true", "iib.delivery.publisher-enabled=false",
        "iib.modules.example.enabled=true", "iib.transports.artemis.enabled=false",
        "iib.transports.kafka.enabled=true", "iib.transports.ibmmq.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaInputRecoveryIntegrationTest {
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "");
    private static final String SCHEMA = "runtime_kafka_recovery_" + SUFFIX;
    private static final String TOPIC = "synthetic.runtime.recovery.in." + SUFFIX;
    private static final String GROUP = "synthetic-runtime-recovery-" + SUFFIX;
    private static final String SOURCE = "synthetic-recovery-source-" + SUFFIX;
    private static boolean topicCreated;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CamelContext camel;

    @MockitoSpyBean
    InboxService inbox;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry properties) throws Exception {
        createInputTopic();
        properties.add("spring.datasource.url", () -> System.getenv("IIB_TEST_POSTGRES_URL"));
        properties.add("spring.datasource.username", KafkaInputRecoveryIntegrationTest::postgresUser);
        properties.add("spring.datasource.password", KafkaInputRecoveryIntegrationTest::postgresPassword);
        properties.add("iib.delivery.schema", () -> SCHEMA);
        properties.add("iib.delivery.create-schema", () -> "true");
        properties.add("spring.liquibase.default-schema", () -> SCHEMA);
        properties.add("spring.liquibase.liquibase-schema", () -> SCHEMA);
        properties.add("iib.transports.kafka.bootstrap-servers", KafkaInputRecoveryIntegrationTest::kafkaBrokers);
        properties.add("iib.modules.example.input-uri", () -> "kafka:" + TOPIC + "?groupId=" + GROUP
                + "&pollTimeoutMs=100&maxPollIntervalMs=60000");
        properties.add("iib.modules.example.source-id", () -> SOURCE);
        properties.add("iib.modules.example.output-transport", () -> "kafka");
        properties.add("iib.modules.example.output-destination", () -> "synthetic.runtime.recovery.out." + SUFFIX);
    }

    @Test
    void uncommittedRecordIsRetriedAfterDatabaseFailureAndCommittedAfterPersistence() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch firstFailure = new CountDownLatch(1);
        CountDownLatch recoveryStarted = new CountDownLatch(1);
        CountDownLatch allowRecovery = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                firstFailure.countDown();
                throw new DataAccessResourceFailureException("synthetic first database acceptance failure");
            }
            recoveryStarted.countDown();
            if (!allowRecovery.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Synthetic recovery was not released");
            }
            return invocation.callRealMethod();
        }).when(inbox).accept(any(InboundMessage.class), any(MessageHandler.class));

        byte[] payload = {0, 1, (byte) 255, 4, 0};
        TopicPartition partition = new TopicPartition(TOPIC, 0);
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafkaBrokers()))) {
            try {
                produce(payload);
                assertThat(firstFailure.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(recoveryStarted.await(10, TimeUnit.SECONDS)).isTrue();

                assertThat(committedOffset(admin, partition)).isNull();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + inboxTable(), Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + outboxTable(), Integer.class)).isZero();
            } finally {
                allowRecovery.countDown();
            }

            awaitReadyEvent();
            awaitCommittedOffset(admin, partition, 1);
            assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + inboxTable(), Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT message_id FROM " + inboxTable(), String.class)).isEqualTo("0");
            assertThat(jdbc.queryForObject("SELECT source FROM " + inboxTable(), String.class)).isEqualTo(SOURCE + ":" + TOPIC + ":0");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + outboxTable(), Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM " + outboxTable(), String.class))
                .isEqualTo(OutboxStatus.READY.getDatabaseValue());
            assertThat(jdbc.queryForObject("SELECT payload FROM " + outboxTable(), byte[].class)).containsExactly(payload);
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + outboxTable(), Integer.class)).isZero();
        }
    }

    private static synchronized void createInputTopic() throws Exception {
        if (topicCreated) {
            return;
        }
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafkaBrokers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            topicCreated = true;
        }
    }

    private static void produce(byte[] payload) throws Exception {
        var settings = Map.<String, Object>of("bootstrap.servers", kafkaBrokers(), "acks", "all",
                "enable.idempotence", true, "max.block.ms", 10000, "request.timeout.ms", 10000,
                "delivery.timeout.ms", 15000);
        try (var producer = new KafkaProducer<byte[], byte[]>(
            settings, new ByteArraySerializer(), new ByteArraySerializer())) {
            var result = producer.send(new ProducerRecord<byte[], byte[]>(TOPIC, 0, null, payload))
                .get(10, TimeUnit.SECONDS);
            assertThat(result.offset()).isZero();
        }
    }

    private static OffsetAndMetadata committedOffset(AdminClient admin, TopicPartition partition) throws Exception {
        return admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(partition);
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
        throw new AssertionError("Kafka retry did not commit a READY event within 10 seconds");
    }

    private static void awaitCommittedOffset(AdminClient admin, TopicPartition partition, long expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            OffsetAndMetadata committed = committedOffset(admin, partition);
            if (committed != null && committed.offset() == expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Kafka offset was not committed after database persistence within 10 seconds");
    }

    @AfterEach
    void stopConsumerBeforeTopicCleanup() throws Exception {
        camel.getRouteController().stopRoute("example.receive", 10, TimeUnit.SECONDS);
    }

    @AfterAll
    static void removeOwnedResources() throws Exception {
        try {
            if (topicCreated) {
                try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafkaBrokers()))) {
                    admin.deleteTopics(List.of(TOPIC)).all().get(10, TimeUnit.SECONDS);
                }
            }
        } finally {
            var dataSource = new DriverManagerDataSource(
                System.getenv("IIB_TEST_POSTGRES_URL"), postgresUser(), postgresPassword());
            new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
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
}
