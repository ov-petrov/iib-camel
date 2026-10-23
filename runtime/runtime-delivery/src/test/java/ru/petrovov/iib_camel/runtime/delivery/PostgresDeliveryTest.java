package ru.petrovov.iib_camel.runtime.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.petrovov.iib_camel.runtime.message.InboundMessage;
import ru.petrovov.iib_camel.runtime.message.MessageHeader;
import ru.petrovov.iib_camel.runtime.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime.transport.TransportSender;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "IIB_TEST_POSTGRES_URL", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
class PostgresDeliveryTest {
    private static final String SCHEMA = "runtime_delivery_test_" + UUID.randomUUID().toString().replace("-", "");
    private static DriverManagerDataSource base;
    private static DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private InboxService inbox;
    private DeliveryProperties properties;

    @BeforeAll
    static void migrate() {
        String url = System.getenv("IIB_TEST_POSTGRES_URL");
        String user = System.getenv().getOrDefault("IIB_TEST_POSTGRES_USER", "postgres");
        String password = System.getenv().getOrDefault("IIB_TEST_POSTGRES_PASSWORD", "");
        base = new DriverManagerDataSource(url, user, password);
        Flyway.configure().dataSource(base).schemas(SCHEMA).defaultSchema(SCHEMA)
                .locations("classpath:db/migration/runtime").load().migrate();
        dataSource = new DriverManagerDataSource(url, user, password);
        Properties connection = new Properties();
        connection.setProperty("currentSchema", SCHEMA);
        dataSource.setConnectionProperties(connection);
        new JdbcTemplate(dataSource).execute("CREATE TABLE delivery_test_business(id TEXT PRIMARY KEY)");
    }

    @AfterAll
    static void removeOwnedSchema() {
        if (base != null) {
            new JdbcTemplate(base).execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach
    void reset() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE runtime_outbox, runtime_inbox, delivery_test_business");
        inbox = new InboxService(dataSource);
        properties = new DeliveryProperties();
    }

    @Test
    void repeatedInputRunsHandlerOnceAndCommitsTwoOutgoingSnapshots() throws Exception {
        InboundMessage input = inbound("input-1", new byte[] {0, -1, 3});
        byte[] payload = new byte[] {1, 0, -1};
        byte[] binaryHeader = new byte[] {0, -1, 10};
        List<MessageHeader> headers = List.of(new MessageHeader("repeated", "BYTES", binaryHeader),
                MessageHeader.text("repeated", "second"));
        OutboundMessage first = new OutboundMessage(UUID.randomUUID(), "test", "synthetic-output", payload, headers, "key-1");
        OutboundMessage second = outbound();
        AtomicInteger calls = new AtomicInteger();
        MessageHandler handler = message -> {
            calls.incrementAndGet();
            jdbc.update("INSERT INTO delivery_test_business VALUES (?)", "business-1");
            return List.of(first, second);
        };
        assertEquals(Acceptance.ACCEPTED, inbox.accept(input, handler));
        payload[0] = 99;
        binaryHeader[0] = 99;
        assertEquals(Acceptance.DUPLICATE, inbox.accept(input, handler));
        assertEquals(1, calls.get());
        assertEquals(1, count("runtime_inbox"));
        assertEquals(1, count("delivery_test_business"));
        assertEquals(2, count("runtime_outbox"));
        List<OutboundMessage> sent = new CopyOnWriteArrayList<>();
        assertEquals(2, dispatcher(sent::add).dispatchOnce());
        OutboundMessage restored = sent.stream().filter(event -> event.id().equals(first.id())).findFirst().orElseThrow();
        assertArrayEquals(new byte[] {1, 0, -1}, restored.payload());
        assertArrayEquals(new byte[] {0, -1, 10}, restored.headers().getFirst().value());
        assertEquals(List.of("repeated", "repeated"), restored.headers().stream().map(MessageHeader::name).toList());
        assertEquals("BYTES", restored.headers().getFirst().type());
        assertEquals("key-1", restored.key());
    }

    @Test
    void checkedHandlerFailureRollsBackBusinessInboxAndOutbox() throws Exception {
        InboundMessage input = inbound("input-1", new byte[] {1});
        IOException original = new IOException("synthetic handler failure");
        IOException actual = assertThrows(IOException.class, () -> inbox.accept(input, message -> {
            jdbc.update("INSERT INTO delivery_test_business VALUES (?)", "business-1");
            throw original;
        }));
        assertEquals(original, actual);
        assertEquals(0, count("runtime_inbox"));
        assertEquals(0, count("delivery_test_business"));
        assertEquals(0, count("runtime_outbox"));
        assertEquals(Acceptance.ACCEPTED, inbox.accept(input, message -> List.of(outbound())));
    }

    @Test
    void failurePersistingSecondOutgoingMessageRollsBackEveryWrite() {
        OutboundMessage duplicateId = outbound();
        assertThrows(DataAccessException.class, () -> inbox.accept(inbound("input-1", new byte[] {1}), message -> {
            jdbc.update("INSERT INTO delivery_test_business VALUES (?)", "business-1");
            return List.of(duplicateId, duplicateId);
        }));
        assertEquals(0, count("runtime_inbox"));
        assertEquals(0, count("delivery_test_business"));
        assertEquals(0, count("runtime_outbox"));
    }

    @Test
    void conflictingIdentityIsRejectedWithoutRunningHandler() throws Exception {
        inbox.accept(inbound("input-1", new byte[] {1}), message -> List.of());
        AtomicInteger called = new AtomicInteger();
        assertThrows(ConflictingMessageException.class,
                () -> inbox.accept(inbound("input-1", new byte[] {2}), message -> {
                    called.incrementAndGet();
                    return List.of(outbound());
                }));
        assertEquals(0, called.get());
        assertEquals(1, count("runtime_inbox"));
        assertEquals(0, count("runtime_outbox"));
    }

    @Test
    void concurrentRepeatedInputStillRunsHandlerOnce() throws Exception {
        AtomicInteger called = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        InboundMessage input = inbound("input-1", new byte[] {1});
        MessageHandler handler = message -> {
            called.incrementAndGet();
            jdbc.update("INSERT INTO delivery_test_business VALUES (?)", "business-1");
            return List.of(outbound());
        };
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> { start.await(); return inbox.accept(input, handler); });
            var second = workers.submit(() -> { start.await(); return inbox.accept(input, handler); });
            start.countDown();
            assertEquals(Set.of(Acceptance.ACCEPTED, Acceptance.DUPLICATE),
                    Set.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
        }
        assertEquals(1, called.get());
        assertEquals(1, count("delivery_test_business"));
        assertEquals(1, count("runtime_outbox"));
    }

    @Test
    void concurrentWorkersClaimSeparateBatchesAndPublishOutsideTransactions() throws Exception {
        List<OutboundMessage> outgoing = new ArrayList<>();
        for (int index = 0; index < 12; index++) outgoing.add(outbound());
        inbox.accept(inbound("input-1", new byte[] {1}), message -> outgoing);
        properties.setBatchSize(6);
        Set<UUID> sent = ConcurrentHashMap.newKeySet();
        CountDownLatch bothSending = new CountDownLatch(2);
        SenderAction action = message -> {
            assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
            bothSending.countDown();
            assertTrue(bothSending.await(20, TimeUnit.SECONDS));
            assertTrue(sent.add(message.id()));
        };
        OutboxDispatcher first = dispatcher(action);
        OutboxDispatcher second = dispatcher(action);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = workers.submit(first::dispatchOnce);
            var two = workers.submit(second::dispatchOnce);
            assertEquals(6, one.get(30, TimeUnit.SECONDS));
            assertEquals(6, two.get(30, TimeUnit.SECONDS));
        }
        assertEquals(12, sent.size());
        assertEquals(12, jdbc.queryForObject("SELECT count(*) FROM runtime_outbox WHERE status = 'PROCESSED'", Integer.class));
    }

    @Test
    void expiredClaimIsRecoveredAndBothStaleStatusUpdatesAreFenced() throws Exception {
        inbox.accept(inbound("input-1", new byte[] {1}), message -> List.of(outbound()));
        OutboxStore store = store();
        OutboxStore.Claim oldClaim = store.claim(Set.of("test")).getFirst();
        expireLeases();
        OutboxStore.Claim replacement = store.claim(Set.of("test")).getFirst();
        assertEquals(oldClaim.message().id(), replacement.message().id());
        assertNotEquals(oldClaim.token(), replacement.token());
        assertEquals(2, replacement.attempt());
        assertFalse(store.processed(oldClaim));
        assertFalse(store.failed(oldClaim, new IOException("stale failure")));
        assertEquals("IN_PROGRESS", status());
        assertTrue(store.processed(replacement));
        assertEquals("PROCESSED", status());
    }

    @Test
    void expiredFinalAttemptBecomesFailedAndFencesOldOwner() throws Exception {
        properties.setMaxAttempts(1);
        inbox.accept(inbound("input-1", new byte[] {1}), message -> List.of(outbound()));
        OutboxStore store = store();
        OutboxStore.Claim oldClaim = store.claim(Set.of("test")).getFirst();
        expireLeases();
        assertTrue(store.claim(Set.of("test")).isEmpty());
        assertEquals("FAILED", status());
        assertFalse(store.processed(oldClaim));
        assertFalse(store.failed(oldClaim, new IOException("stale failure")));
    }

    @Test
    void confirmedPublishWithDatabaseMarkFailureRetriesIdenticalEvent() throws Exception {
        OutboundMessage event = outbound();
        inbox.accept(inbound("input-1", new byte[] {1}), message -> List.of(event));
        List<UUID> sent = new CopyOnWriteArrayList<>();
        OutboxDispatcher dispatcher = new OutboxDispatcher(new FailProcessedOnceDataSource(dataSource), properties,
                List.of(sender(message -> sent.add(message.id()))));
        assertThrows(DataAccessException.class, dispatcher::dispatchOnce);
        assertEquals("IN_PROGRESS", status());
        assertEquals(1, sent.size());
        expireLeases();
        assertEquals(1, dispatcher.dispatchOnce());
        assertEquals(List.of(event.id(), event.id()), sent);
        assertEquals("PROCESSED", status());
        assertEquals(2, jdbc.queryForObject("SELECT attempt_count FROM runtime_outbox", Integer.class));
    }

    @Test
    void failedSendWaitsForBackoffThenStopsAtMaximumAttempts() throws Exception {
        properties.setMaxAttempts(2);
        properties.setBackoff(Duration.ofMinutes(1));
        inbox.accept(inbound("input-1", new byte[] {1}), message -> List.of(outbound()));
        AtomicInteger calls = new AtomicInteger();
        OutboxDispatcher dispatcher = dispatcher(message -> {
            calls.incrementAndGet();
            throw new IOException("synthetic broker unavailable");
        });
        assertEquals(0, dispatcher.dispatchOnce());
        assertEquals("READY", status());
        assertEquals(0, dispatcher.dispatchOnce());
        assertEquals(1, calls.get());
        jdbc.update("UPDATE runtime_outbox SET available_at = clock_timestamp() - INTERVAL '1 second'");
        assertEquals(0, dispatcher.dispatchOnce());
        assertEquals("FAILED", status());
        assertEquals(0, dispatcher.dispatchOnce());
        assertEquals(2, calls.get());
        assertEquals(2, jdbc.queryForObject("SELECT attempt_count FROM runtime_outbox", Integer.class));
    }

    @Test
    void unknownTransportFailsBeforeAnyClaimAtStartupOrLater() throws Exception {
        OutboxDispatcher existing = dispatcher(message -> {});
        OutboundMessage event = new OutboundMessage(UUID.randomUUID(), "unconfigured", "synthetic-output",
                new byte[] {1}, List.of(), null);
        inbox.accept(inbound("input-1", new byte[] {1}), message -> List.of(event));
        assertThrows(IllegalStateException.class, () -> dispatcher(message -> {}));
        assertThrows(IllegalStateException.class, existing::dispatchOnce);
        assertEquals("READY", status());
        assertEquals(0, jdbc.queryForObject("SELECT attempt_count FROM runtime_outbox", Integer.class));
    }

    @Test
    void callerTransactionCannotEncloseBrokerPublish() throws Exception {
        inbox.accept(inbound("input-1", new byte[] {1}), message -> List.of(outbound()));
        AtomicInteger sends = new AtomicInteger();
        OutboxDispatcher dispatcher = dispatcher(message -> sends.incrementAndGet());
        TransactionTemplate caller = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        caller.executeWithoutResult(status -> assertThrows(IllegalStateException.class, dispatcher::dispatchOnce));
        assertEquals(0, sends.get());
        assertEquals("READY", status());
    }

    @Test
    void publisherSchedulingIsOptInAndDatabaseServicesAreAvailable() {
        ApplicationContextRunner contexts = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RuntimeDeliveryAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource)
                .withPropertyValues("iib.delivery.enabled=true");
        contexts.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(InboxService.class);
            assertThat(context).hasSingleBean(OutboxDispatcher.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
        contexts.withPropertyValues("iib.delivery.publisher-enabled=true", "iib.delivery.scheduler.fixed-delay=PT24H")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
                });
    }

    private OutboxStore store() {
        return new OutboxStore(dataSource, properties, new ObjectMapper());
    }

    private OutboxDispatcher dispatcher(SenderAction action) {
        return new OutboxDispatcher(dataSource, properties, List.of(sender(action)));
    }

    private static TransportSender sender(SenderAction action) {
        return new TransportSender() {
            @Override public String transport() { return "test"; }
            @Override public void send(OutboundMessage message) throws Exception { action.send(message); }
        };
    }

    private static InboundMessage inbound(String identity, byte[] payload) {
        return new InboundMessage("synthetic-module", "synthetic-source", identity, payload, List.of());
    }

    private static OutboundMessage outbound() {
        return new OutboundMessage(UUID.randomUUID(), "test", "synthetic-output", new byte[] {1, 0, -1},
                List.of(MessageHeader.text("synthetic-header", "value")), null);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM runtime_outbox", String.class);
    }

    private void expireLeases() {
        jdbc.update("UPDATE runtime_outbox SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE status = 'IN_PROGRESS'");
    }

    @FunctionalInterface
    private interface SenderAction {
        void send(OutboundMessage message) throws Exception;
    }

    private static final class FailProcessedOnceDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final AtomicBoolean fail = new AtomicBoolean(true);

        private FailProcessedOnceDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return wrap(delegate.getConnection(username, password));
        }

        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(connection, arguments);
                            if (method.getName().equals("prepareStatement") && arguments[0] instanceof String sql
                                    && sql.contains("SET status = 'PROCESSED'")) {
                                PreparedStatement statement = (PreparedStatement) result;
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[] {PreparedStatement.class}, (ignored, operation, parameters) -> {
                                            if (operation.getName().equals("executeUpdate") && fail.compareAndSet(true, false)) {
                                                throw new SQLException("synthetic database mark failure", "08006");
                                            }
                                            try {
                                                return operation.invoke(statement, parameters);
                                            } catch (InvocationTargetException failure) {
                                                throw failure.getCause();
                                            }
                                        });
                            }
                            return result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
