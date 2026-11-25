package ru.petrovov.iib_camel.runtime_lite.delivery;

import lombok.RequiredArgsConstructor;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
import ru.petrovov.iib_camel.runtime_lite.message.MessageBody;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.outbox.MessagePublisher;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxDispatcher;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxMessage;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxStatus;
import ru.petrovov.iib_camel.runtime_lite.processor.MessageProcessor;
import ru.petrovov.iib_camel.runtime_lite.transport.MessageSender;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = RuntimeLiteApplication.class)
@ActiveProfiles("int-test")
@Import(PostgresDeliveryTest.Fixtures.class)
@EnabledIfSystemProperty(named = "runtime-lite.integration-test", matches = "true")
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
    private DataSource dataSource;

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
        registry.add("runtime-lite.outbox.scheduling-enabled", () -> false);
        registry.add("runtime-lite.routes.sample.input-queue", () -> "DEMO.IN");
        registry.add("runtime-lite.routes.sample.processor", () -> "sampleProcessor");
        registry.add("runtime-lite.routes.other.input-queue", () -> "DEMO.OTHER.IN");
        registry.add("runtime-lite.routes.other.processor", () -> "sampleProcessor");
        registry.add("runtime-lite.soap.plain-jms-destinations",
            () -> "DEMO.IN,DEMO.OTHER.IN,demo.queue,demo.extra,demo.dynamic");
    }

    @BeforeEach
    void reset() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + REFERENCE_TABLE + " (code TEXT PRIMARY KEY, label TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + BUSINESS_TABLE + " (message_id TEXT, label TEXT NOT NULL)");
        jdbc.execute("ALTER TABLE " + tables.outbox() + " DROP CONSTRAINT IF EXISTS reject_sent");
        jdbc.execute("TRUNCATE " + tables.enrichment() + ", " + tables.inboundMessages() + ", " + tables.outbox() + ", " + BUSINESS_TABLE + ", " + REFERENCE_TABLE);
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
            .contains("inbound_message", "outbox_message", "enrichment_workflow", "databasechangelog", "databasechangeloglock",
                "reference_item", "processed_item");
        assertThat(references.findById("A")).contains(new ReferenceItem("A", "Synthetic label"));
    }

    @Test
    void liquibaseCanReopenExistingSchemaWithoutChangingMigrationHistory() throws Exception {
        String historyQuery = """
            SELECT id, author, filename, md5sum, exectype, dateexecuted, orderexecuted
            FROM databasechangelog
            ORDER BY filename, author, id
            """;
        var history = jdbc.queryForList(historyQuery);
        assertThat(history).isNotEmpty();

        liquibase.afterPropertiesSet();

        assertThat(jdbc.queryForList(historyQuery)).isEqualTo(history);
    }

    @Test
    void rerunsChangedChangesetsWithoutLosingDeliveryData() throws Exception {
        String schema = "lite_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String changelog = "db/changelog/db.changelog-master.yaml";
        UUID outboxId = UUID.randomUUID();
        jdbc.execute("CREATE SCHEMA " + schema);
        try (var connection = dataSource.getConnection()) {
            var database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setDefaultSchemaName(schema);
            database.setLiquibaseSchemaName(schema);
            try (var migration = new Liquibase(changelog, new ClassLoaderResourceAccessor(), database)) {
                migration.setChangeLogParameter("deliverySchema", schema);
                migration.update(new Contexts(), new LabelExpression());
                jdbc.update("INSERT INTO " + schema + ".inbound_message "
                        + "(route_id, message_id, source_destination, processor_id, body, headers) "
                        + "VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))",
                    "sample", "message-1", "DEMO.IN", "sampleProcessor", "original payload", "{}");
                jdbc.update("INSERT INTO " + schema + ".outbox_message "
                        + "(id, transport, destination, body, headers, status, jms_reply_to) "
                        + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)",
                    outboxId, "JMS", "demo.queue", "sample payload", "{}", "CANCELLED", "demo.reply");

                var indexes = List.of(schema + ".outbox_message_dispatch_idx", schema + ".enrichment_workflow_ready_idx",
                    schema + ".enrichment_workflow_dispatch_deadline_idx", schema + ".enrichment_workflow_reply_deadline_idx");
                indexes.forEach(index -> jdbc.execute("DROP INDEX " + index));
                String history = schema + ".databasechangelog";
                String changesetFilter = " WHERE id = ? AND author = ? AND filename = ?";
                var changedChangesetIds = List.of("table-outbox-message", "table-enrichment-workflow");
                changedChangesetIds.forEach(id -> assertThat(jdbc.update(
                    "UPDATE " + history + " SET md5sum = ?" + changesetFilter,
                    "9:00000000000000000000000000000000", id, "runtime-lite", changelog)).isEqualTo(1));

                migration.update(new Contexts(), new LabelExpression());

                changedChangesetIds.forEach(id -> assertThat(jdbc.queryForObject(
                    "SELECT exectype FROM " + history + changesetFilter,
                    String.class, id, "runtime-lite", changelog)).isEqualTo("RERAN"));
                assertThat(indexes).allSatisfy(index ->
                    assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, index)).isTrue());
                assertThat(jdbc.queryForObject("SELECT body FROM " + schema + ".outbox_message WHERE id = ?",
                    String.class, outboxId)).isEqualTo("sample payload");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".inbound_message", Integer.class))
                    .isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".enrichment_workflow", Integer.class))
                    .isZero();
                assertThat(jdbc.queryForObject("SELECT jms_reply_to FROM " + schema + ".outbox_message WHERE id = ?",
                    String.class, outboxId)).isEqualTo("demo.reply");
            }
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void createsNativeXmlColumnsAndTimeZoneAwareTimestamps() {
        assertThat(jdbc.queryForList("""
            SELECT table_name || '.' || column_name || ':' || data_type FROM information_schema.columns
            WHERE table_schema = ? AND column_name IN ('body', 'body_xml', 'input_xml', 'reply_xml')
            """, String.class, DELIVERY_SCHEMA)).containsExactlyInAnyOrder(
                "inbound_message.body:text", "inbound_message.body_xml:xml",
                "outbox_message.body:text", "outbox_message.body_xml:xml", "enrichment_workflow.reply_xml:xml");
        assertThat(jdbc.queryForList("""
            SELECT data_type || ':' || datetime_precision FROM information_schema.columns
            WHERE table_schema = ? AND data_type LIKE 'timestamp%'
                AND table_name IN ('inbound_message', 'outbox_message', 'enrichment_workflow')
            """, String.class, DELIVERY_SCHEMA)).hasSize(11).containsOnly("timestamp with time zone:6");
        assertThat(jdbc.queryForList("""
            SELECT column_default FROM information_schema.columns
            WHERE table_schema = ? AND data_type = 'timestamp with time zone' AND column_default IS NOT NULL
                AND table_name IN ('inbound_message', 'outbox_message', 'enrichment_workflow')
            """, String.class, DELIVERY_SCHEMA)).hasSize(4).allSatisfy(value -> assertThat(value).contains("clock_timestamp()"));
    }

    @Test
    void preservesUnicodeTextThroughOutbox() {
        String body = "Привет 🌍";
        outboxTransaction.executeWithoutResult(status -> publisher.send(OutboundMessage.of(Transport.KAFKA, "demo.result", body)));

        dispatcher.dispatchBatch();

        assertThat(sender.calls).singleElement().satisfies(message -> assertThat(message.message().body()).isEqualTo(body));
    }

    @Test
    void commitsReferenceDrivenBusinessWriteAndAllMessagesTogether() {
        processor.beforeWrite = () -> assertThat(jdbc.queryForObject(
            "SELECT body FROM " + tables.inboundMessages(), String.class)).isEqualTo("A");

        processing.process("sample", inbound("ID:one"));

        assertThat(count(tables.inboundMessages())).isEqualTo(1);
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
    void processorChoosesAndPersistsDestinationForEachInputBeforeDispatch() {
        processing.process("sample", inbound("ID:kafka"));
        processing.process("sample", new InboundMessage("ID:jms", "A", Map.of("target", "jms")));

        assertThat(jdbc.queryForList("SELECT transport, destination FROM " + tables.outbox()
            + " WHERE destination IN (?, ?)", "demo.result", "demo.dynamic"))
            .extracting(row -> row.get("transport") + ":" + row.get("destination"))
            .containsExactlyInAnyOrder("KAFKA:demo.result", "JMS:demo.dynamic");

        assertThat(dispatcher.dispatchBatch()).isEqualTo(4);
        assertThat(sender.calls).extracting(message ->
            message.message().transport().name() + ":" + message.message().destination())
            .contains("KAFKA:demo.result", "JMS:demo.dynamic");
    }

    @Test
    void rollsBackBusinessWriteReceiptAndAdditionalMessagesBeforeRedelivery() {
        processor.fail = true;

        assertThatThrownBy(() -> processing.process("sample", inbound("ID:retry")))
            .isInstanceOf(IllegalStateException.class);

        assertThat(count(tables.inboundMessages())).isZero();
        assertThat(count(tables.outbox())).isZero();
        assertThat(count(BUSINESS_TABLE)).isZero();

        processor.fail = false;
        processing.process("sample", inbound("ID:retry"));

        assertThat(processor.invocations).hasValue(2);
        assertThat(count(tables.inboundMessages())).isEqualTo(1);
        assertThat(count(tables.outbox())).isEqualTo(2);
        assertThat(count(BUSINESS_TABLE)).isEqualTo(1);
    }

    @Test
    void ignoresBrokerRedeliveryAfterDatabaseCommit() {
        processing.process("sample", inbound("ID:duplicate"));
        processing.process("sample", new InboundMessage("ID:duplicate", "different body", Map.of()));

        assertThat(processor.invocations).hasValue(1);
        assertThat(count(tables.inboundMessages())).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
            SELECT route_id, message_id, source_destination, processor_id, body, body_xml::text AS body_xml,
                headers ->> 'sample_header' AS sample_header
            FROM %s
            """.formatted(tables.inboundMessages())))
            .containsEntry("route_id", "sample")
            .containsEntry("message_id", "ID:duplicate")
            .containsEntry("source_destination", "DEMO.IN")
            .containsEntry("processor_id", "sampleProcessor")
            .containsEntry("body", "A")
            .containsEntry("body_xml", null)
            .containsEntry("sample_header", "sample_value");
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
                        """, Integer.class, "INSERT INTO " + tables.inboundMessages() + "%")).isEqualTo(1));
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
        assertThat(count(tables.inboundMessages())).isEqualTo(1);
        assertThat(count(tables.outbox())).isEqualTo(2);
    }

    @Test
    void keepsDeduplicationIndependentForEachRoute() {
        processing.process("sample", inbound("ID:shared"));
        processing.process("other", inbound("ID:shared"));

        assertThat(processor.invocations).hasValue(2);
        assertThat(count(tables.inboundMessages())).isEqualTo(2);
        assertThat(count(tables.outbox())).isEqualTo(4);
    }

    @Test
    void refusesPublicationWithoutAnExistingDatabaseTransaction() {
        assertThatThrownBy(() -> publisher.send(OutboundMessage.of(Transport.KAFKA, "demo.result", "payload")))
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
        assertThat(sender.calls).filteredOn(message -> message.message().transport() == Transport.KAFKA).singleElement()
            .satisfies(message -> {
                assertThat(message.message().destination()).isEqualTo("demo.result");
                assertThat(message.message().key()).isEqualTo("A");
                assertThat(message.message().body()).isEqualTo("Synthetic label");
                assertThat(message.message().headers()).containsEntry("sample_header", "sample_value");
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
        var sendFinishedAt = new AtomicReference<OffsetDateTime>();
        Runnable finishSend = () -> sendFinishedAt.set(jdbc.queryForObject(
            "SELECT clock_timestamp() FROM pg_sleep(0.02)", OffsetDateTime.class));
        sender.action = message -> {
            finishSend.run();
            throw new IOException("Synthetic transport failure");
        };

        dispatcher.dispatchBatch();

        assertThat(jdbc.queryForObject("SELECT available_at FROM " + tables.outbox() + " WHERE id = ?",
            OffsetDateTime.class, id)).isAfterOrEqualTo(sendFinishedAt.get().plus(properties.getRetryDelay()));

        makeAvailable(id);
        sender.action = message -> finishSend.run();

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

        assertThat(status(id)).isEqualTo(OutboxStatus.SENT);
        assertThat(attempts(id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sent_at FROM " + tables.outbox() + " WHERE id = ?",
            OffsetDateTime.class, id)).isAfterOrEqualTo(sendFinishedAt.get());
        assertThat(sender.calls).extracting(OutboxMessage::id).containsExactly(id, id);
        assertThat(sender.calls).allSatisfy(message -> assertThat(message.message().body()).isEqualTo("payload"));
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
        return outboxTransaction.execute(status -> publisher.send(OutboundMessage.of(Transport.KAFKA, "demo.result", "payload")));
    }

    private void makeAvailable(UUID id) {
        jdbc.update("UPDATE " + tables.outbox() + " SET available_at = clock_timestamp() WHERE id = ?", id);
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
    @EnableJdbcRepositories(basePackageClasses = RuntimeLiteApplication.class, considerNestedRepositories = true)
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
        public OutboundMessage process(InboundMessage message) {
            invocations.incrementAndGet();
            beforeWrite.run();
            var reference = references.lookup(message.body()).orElseThrow();
            jdbc.update("INSERT INTO " + BUSINESS_TABLE + " (message_id, label) VALUES (?, ?)",
                message.id(), reference.label());
            publisher.send(OutboundMessage.of(Transport.JMS, "demo.extra", reference.label()));
            if (fail) {
                throw new IllegalStateException("Synthetic processing failure");
            }
            var outbound = OutboundMessage.builder()
                .payload(MessageBody.text(reference.label()))
                .key(reference.code())
                .headers(message.headers());
            if ("jms".equals(message.headers().get("target"))) {
                return outbound.transport(Transport.JMS).destination("demo.dynamic").build();
            }
            return outbound.transport(Transport.KAFKA).destination("demo.result").build();
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
