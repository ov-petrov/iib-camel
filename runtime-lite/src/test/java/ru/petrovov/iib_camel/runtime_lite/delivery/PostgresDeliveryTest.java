package ru.petrovov.iib_camel.runtime_lite.delivery;

import lombok.RequiredArgsConstructor;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import ru.petrovov.iib_camel.runtime_lite.RuntimeLiteApplication;
import ru.petrovov.iib_camel.runtime_lite.config.OutboxProperties;
import ru.petrovov.iib_camel.runtime_lite.inbox.MessageProcessingService;
import ru.petrovov.iib_camel.runtime_lite.jdbc.DeliveryTables;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.MessagePayload;
import ru.petrovov.iib_camel.runtime_lite.outbox.MessagePublisher;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxDispatcher;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxMessage;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxStatus;
import ru.petrovov.iib_camel.runtime_lite.processor.MessageProcessor;
import ru.petrovov.iib_camel.runtime_lite.transport.MessageSender;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = RuntimeLiteApplication.class)
@ActiveProfiles({"test", "db-test"})
@Import(PostgresDeliveryTest.Fixtures.class)
@EnabledIfEnvironmentVariable(named = "IIB_LITE_TEST_POSTGRES_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresDeliveryTest {

    private static final String DELIVERY_SCHEMA = "lite_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String BUSINESS_TABLE = "processed_item";
    private static final String REFERENCE_TABLE = "reference_item";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryTables tables;

    @Autowired
    private SpringLiquibase liquibase;

    @Autowired
    private MessageProcessingService processing;

    @Autowired
    private MessagePublisher publisher;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private TransactionTemplate outboxTransaction;

    @Autowired
    private SampleProcessor processor;

    @Autowired
    private ReferenceRepository references;

    @Autowired
    private RecordingSender sender;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.liquibase.default-schema", () -> DELIVERY_SCHEMA);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + REFERENCE_TABLE + " (code TEXT PRIMARY KEY, label TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + BUSINESS_TABLE + " (message_id TEXT, label TEXT NOT NULL)");
        jdbc.execute("ALTER TABLE " + tables.outbox() + " DROP CONSTRAINT IF EXISTS reject_sent");
        jdbc.execute("TRUNCATE " + tables.receipts() + ", " + tables.outbox() + ", " + BUSINESS_TABLE + ", " + REFERENCE_TABLE);
        jdbc.update("INSERT INTO " + REFERENCE_TABLE + " (code, label) VALUES (?, ?)", "A", "Synthetic label");
        processor.invocations.set(0);
        processor.fail = false;
        processor.beforeWrite = () -> { };
        sender.calls.clear();
        sender.action = message -> { };
        properties.setBatchSize(20);
        properties.setMaxAttempts(10);
        properties.setRetryDelay(Duration.ofSeconds(10));
    }

    @AfterAll
    void removeSchema() {
        jdbc.execute("DROP SCHEMA " + DELIVERY_SCHEMA + " CASCADE");
    }

    @Test
    void usesOneSchemaForConnectionLiquibaseAndBusinessTables() {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(DELIVERY_SCHEMA);
        assertThat(jdbc.queryForList("""
            SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = ?
            """, String.class, DELIVERY_SCHEMA))
            .contains("inbound_receipt", "outbox_message", "databasechangelog", "databasechangeloglock",
                "reference_item", "processed_item");
        assertThat(references.findById("A")).contains(new ReferenceItem("A", "Synthetic label"));
    }

    @Test
    void liquibaseCanReopenExistingSchemaWithoutChangingMigrationHistory() throws Exception {
        var checksums = jdbc.queryForList("SELECT md5sum FROM databasechangelog ORDER BY id", String.class);

        liquibase.afterPropertiesSet();

        assertThat(jdbc.queryForList("SELECT md5sum FROM databasechangelog ORDER BY id", String.class))
            .hasSize(1).isEqualTo(checksums);
    }

    @Test
    void createsOutboxBodyAsText() {
        assertThat(jdbc.queryForObject("""
            SELECT data_type FROM information_schema.columns
            WHERE table_schema = ? AND table_name = 'outbox_message' AND column_name = 'body'
            """, String.class, DELIVERY_SCHEMA)).isEqualTo("text");
    }

    @Test
    void preservesUnicodeTextThroughOutbox() {
        String body = "Привет 🌍";
        outboxTransaction.executeWithoutResult(status -> publisher.send("result", MessagePayload.of(body)));

        dispatcher.dispatchBatch();

        assertThat(sender.calls).singleElement().satisfies(message -> assertThat(message.payload().body()).isEqualTo(body));
    }

    @Test
    void commitsReferenceDrivenBusinessWriteAndAllMessagesTogether() {
        processing.process("sample", inbound("ID:one"));

        assertThat(count(tables.receipts())).isEqualTo(1);
        assertThat(count(BUSINESS_TABLE)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT label FROM " + BUSINESS_TABLE, String.class)).isEqualTo("Synthetic label");
        assertThat(count(tables.outbox())).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT transport FROM " + tables.outbox(), String.class))
            .containsExactlyInAnyOrder(Transport.JMS.name(), Transport.KAFKA.name());
        assertThat(jdbc.queryForObject("SELECT message_key FROM " + tables.outbox() + " WHERE transport = ?",
            String.class, Transport.KAFKA.name())).isEqualTo("A");
        assertThat(sender.calls).isEmpty();
    }

    @Test
    void rollsBackBusinessWriteReceiptAndAdditionalMessagesBeforeRedelivery() {
        processor.fail = true;

        assertThatThrownBy(() -> processing.process("sample", inbound("ID:retry")))
            .isInstanceOf(IllegalStateException.class);

        assertThat(count(tables.receipts())).isZero();
        assertThat(count(tables.outbox())).isZero();
        assertThat(count(BUSINESS_TABLE)).isZero();

        processor.fail = false;
        processing.process("sample", inbound("ID:retry"));

        assertThat(processor.invocations).hasValue(2);
        assertThat(count(tables.receipts())).isEqualTo(1);
        assertThat(count(tables.outbox())).isEqualTo(2);
        assertThat(count(BUSINESS_TABLE)).isEqualTo(1);
    }

    @Test
    void ignoresBrokerRedeliveryAfterDatabaseCommit() {
        processing.process("sample", inbound("ID:duplicate"));
        processing.process("sample", inbound("ID:duplicate"));

        assertThat(processor.invocations).hasValue(1);
        assertThat(count(tables.receipts())).isEqualTo(1);
        assertThat(count(tables.outbox())).isEqualTo(2);
        assertThat(count(BUSINESS_TABLE)).isEqualTo(1);
    }

    @Test
    void concurrentDeliveriesOfTheSameMessageProduceOnlyOneBusinessOperation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        processor.beforeWrite = () -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test processing was not released");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Test processing interrupted", failure);
            }
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> processing.process("sample", inbound("ID:concurrent")));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> processing.process("sample", inbound("ID:concurrent")));
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM pg_catalog.pg_stat_activity
                        WHERE wait_event_type = 'Lock' AND query LIKE ?
                        """, Integer.class, "INSERT INTO " + tables.receipts() + "%")).isEqualTo(1));
                assertThat(processor.invocations).hasValue(1);
                release.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }

        assertThat(processor.invocations).hasValue(1);
        assertThat(count(BUSINESS_TABLE)).isEqualTo(1);
        assertThat(count(tables.receipts())).isEqualTo(1);
        assertThat(count(tables.outbox())).isEqualTo(2);
    }

    @Test
    void keepsDeduplicationIndependentForEachRoute() {
        processing.process("sample", inbound("ID:shared"));
        processing.process("other", inbound("ID:shared"));

        assertThat(processor.invocations).hasValue(2);
        assertThat(count(tables.receipts())).isEqualTo(2);
        assertThat(count(tables.outbox())).isEqualTo(4);
    }

    @Test
    void refusesPublicationWithoutAnExistingDatabaseTransaction() {
        assertThatThrownBy(() -> publisher.send("result", MessagePayload.of("payload")))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count(tables.outbox())).isZero();
    }

    @Test
    void marksSentOnlyAfterSenderAcknowledgesAndPreservesEnvelope() {
        processing.process("sample", inbound("ID:delivery"));
        sender.action = message -> assertThat(status(message.id())).isEqualTo(OutboxStatus.PENDING);

        assertThat(dispatcher.dispatchBatch()).isEqualTo(2);

        assertThat(sender.calls).hasSize(2);
        assertThat(sender.calls).allSatisfy(message -> assertThat(status(message.id())).isEqualTo(OutboxStatus.SENT));
        assertThat(sender.calls).filteredOn(message -> message.transport() == Transport.KAFKA).singleElement()
            .satisfies(message -> {
                assertThat(message.destination()).isEqualTo("demo.result");
                assertThat(message.payload().key()).isEqualTo("A");
                assertThat(message.payload().body()).isEqualTo("Synthetic label");
                assertThat(message.payload().headers()).containsEntry("sample_header", "sample_value");
            });
    }

    @Test
    void retriesWithStableIdentityAndStopsAtConfiguredAttemptLimit() {
        UUID id = enqueue();
        properties.setMaxAttempts(2);
        sender.action = message -> { throw new IOException("Synthetic transport failure"); };

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(status(id)).isEqualTo(OutboxStatus.PENDING);
        assertThat(attempts(id)).isEqualTo(1);
        assertThat(dispatcher.dispatchBatch()).isZero();

        makeAvailable(id);
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

        assertThat(status(id)).isEqualTo(OutboxStatus.FAILED);
        assertThat(attempts(id)).isEqualTo(2);
        assertThat(sender.calls).extracting(OutboxMessage::id).containsExactly(id, id);
        assertThat(dispatcher.dispatchBatch()).isZero();
    }

    @Test
    void retriesTransientFailureSuccessfullyWithoutChangingTheMessage() {
        UUID id = enqueue();
        sender.action = message -> { throw new IOException("Synthetic transport failure"); };
        dispatcher.dispatchBatch();
        makeAvailable(id);
        sender.action = message -> { };

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

        assertThat(status(id)).isEqualTo(OutboxStatus.SENT);
        assertThat(attempts(id)).isEqualTo(2);
        assertThat(sender.calls).extracting(OutboxMessage::id).containsExactly(id, id);
        assertThat(sender.calls).allSatisfy(message -> assertThat(message.payload().body()).isEqualTo("payload"));
    }

    @Test
    void concurrentDispatchersSkipRowsAlreadyBeingDelivered() throws Exception {
        enqueue();
        enqueue();
        properties.setBatchSize(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        sender.action = message -> {
            if (first.compareAndSet(true, false)) {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test delivery was not released");
                }
            }
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstDispatch = executor.submit(dispatcher::dispatchBatch);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
                assertThat(sender.calls).hasSize(2);
                assertThat(sender.calls).extracting(OutboxMessage::id).doesNotHaveDuplicates();
            } finally {
                release.countDown();
            }
            assertThat(firstDispatch.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(sender.calls).allSatisfy(message -> assertThat(status(message.id())).isEqualTo(OutboxStatus.SENT));
    }

    @Test
    void databaseFailureAfterBrokerAckLeavesMessageForReplayWithSameIdentity() {
        UUID id = enqueue();
        jdbc.execute("ALTER TABLE " + tables.outbox() + " ADD CONSTRAINT reject_sent CHECK (status <> '"
            + OutboxStatus.SENT.name() + "')");

        assertThatThrownBy(dispatcher::dispatchBatch).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(status(id)).isEqualTo(OutboxStatus.PENDING);
        assertThat(attempts(id)).isZero();

        jdbc.execute("ALTER TABLE " + tables.outbox() + " DROP CONSTRAINT reject_sent");
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

        assertThat(status(id)).isEqualTo(OutboxStatus.SENT);
        assertThat(sender.calls).extracting(OutboxMessage::id).containsExactly(id, id);
    }

    @Test
    void interruptionRollsBackAttemptAndPreservesInterruptFlag() {
        UUID id = enqueue();
        sender.action = message -> { throw new InterruptedException("Synthetic shutdown"); };
        try {
            assertThatThrownBy(dispatcher::dispatchBatch).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(status(id)).isEqualTo(OutboxStatus.PENDING);
        assertThat(attempts(id)).isZero();
    }

    private UUID enqueue() {
        return outboxTransaction.execute(status -> publisher.send("result", MessagePayload.of("payload")));
    }

    private void makeAvailable(UUID id) {
        jdbc.update("UPDATE " + tables.outbox() + " SET available_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int attempts(UUID id) {
        return jdbc.queryForObject("SELECT attempts FROM " + tables.outbox() + " WHERE id = ?", Integer.class, id);
    }

    private OutboxStatus status(UUID id) {
        return OutboxStatus.valueOf(jdbc.queryForObject("SELECT status FROM " + tables.outbox() + " WHERE id = ?",
            String.class, id));
    }

    private static InboundMessage inbound(String id) {
        return new InboundMessage(id, "A", Map.of("sample_header", "sample_value"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJdbcRepositories(basePackageClasses = PostgresDeliveryTest.class, considerNestedRepositories = true)
    static class Fixtures {

        @Bean
        SampleProcessor sampleProcessor(ReferenceRepository references, JdbcTemplate jdbc, MessagePublisher publisher) {
            return new SampleProcessor(references, jdbc, publisher);
        }

        @Bean
        @Primary
        RecordingSender recordingSender() {
            return new RecordingSender();
        }
    }

    @Table(name = "reference_item")
    record ReferenceItem(@Id String code, String label) {
    }

    interface ReferenceRepository extends CrudRepository<ReferenceItem, String> {

        @Query("SELECT code, label FROM #{#qualifiedTableName} WHERE code = :code")
        Optional<ReferenceItem> lookup(@Param("code") String code);
    }

    @RequiredArgsConstructor
    static class SampleProcessor implements MessageProcessor {

        private final ReferenceRepository references;
        private final JdbcTemplate jdbc;
        private final MessagePublisher publisher;
        private final AtomicInteger invocations = new AtomicInteger();
        private volatile boolean fail;
        private volatile Runnable beforeWrite = () -> { };

        @Override
        public MessagePayload process(InboundMessage message) {
            invocations.incrementAndGet();
            beforeWrite.run();
            var reference = references.lookup(message.body()).orElseThrow();
            jdbc.update("INSERT INTO " + BUSINESS_TABLE + " (message_id, label) VALUES (?, ?)",
                message.id(), reference.label());
            publisher.send("extra", MessagePayload.of(reference.label()));
            if (fail) {
                throw new IllegalStateException("Synthetic processing failure");
            }
            return new MessagePayload(reference.label(), reference.code(), message.headers());
        }
    }

    static class RecordingSender implements MessageSender {

        private final ConcurrentLinkedQueue<OutboxMessage> calls = new ConcurrentLinkedQueue<>();
        private volatile SendAction action = message -> { };

        @Override
        public void send(OutboxMessage message) throws Exception {
            calls.add(message);
            action.send(message);
        }
    }

    @FunctionalInterface
    interface SendAction {

        void send(OutboxMessage message) throws Exception;
    }
}
