package ru.petrovov.iib_camel.runtime_lite.enrichment;

import jakarta.jms.TextMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.petrovov.iib_camel.runtime_lite.RuntimeLiteApplication;
import ru.petrovov.iib_camel.runtime_lite.config.OutboxProperties;
import ru.petrovov.iib_camel.runtime_lite.inbox.MessageProcessingService;
import ru.petrovov.iib_camel.runtime_lite.jdbc.DeliveryTables;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.MessageBody;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxDispatcher;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxMessage;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichingMessageProcessor;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichmentFailure;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichmentResult;
import ru.petrovov.iib_camel.runtime_lite.processor.ExternalEnrichmentRejectedException;
import ru.petrovov.iib_camel.runtime_lite.processor.JmsEnrichmentOperation;
import ru.petrovov.iib_camel.runtime_lite.transport.MessageSender;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = RuntimeLiteApplication.class)
@ActiveProfiles("int-test")
@Import(PostgresEnrichmentWorkflowTest.Fixtures.class)
@EnabledIfSystemProperty(named = "runtime-lite.integration-test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresEnrichmentWorkflowTest {

    private static final String SCHEMA = "enrichment_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String REPLY_QUEUE = "TEST.REPLY";
    private static final String OUTPUT_QUEUE = "TEST.OUTPUT";
    private static final String ERROR_QUEUE = "TEST.ERROR";
    private static final String FAILURE_HEADER = "enrichment_failure";
    private static final String DIRECT_RESULT = "not enriched";
    private static final InboundMessage INPUT = new InboundMessage("input-1", "input", Map.of("origin", "synthetic"));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryTables tables;

    @Autowired
    private MessageProcessingService processing;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private EnrichmentReplyReceiver receiver;

    @Autowired
    private EnrichmentWorker worker;

    @Autowired
    private RecordingSender sender;

    @Autowired
    private OutboxProperties outboxProperties;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.liquibase.default-schema", () -> SCHEMA);
        registry.add("runtime-lite.enrichment.scheduling-enabled", () -> false);
        registry.add("runtime-lite.outbox.scheduling-enabled", () -> false);
        registry.add("runtime-lite.routes.workflow.input-queue", () -> "TEST.INPUT");
        registry.add("runtime-lite.routes.workflow.processor", () -> "workflowProcessor");
        registry.add("runtime-lite.enrichment.operations.lookup-v1.request-queue", () -> "TEST.REQUEST");
        registry.add("runtime-lite.enrichment.operations.lookup-v1.reply-queue", () -> REPLY_QUEUE);
        registry.add("runtime-lite.enrichment.default-reply-timeout", () -> "20s");
        registry.add("runtime-lite.soap.plain-jms-destinations",
            () -> String.join(",", "TEST.INPUT", "TEST.REQUEST", REPLY_QUEUE, OUTPUT_QUEUE, ERROR_QUEUE, "TEST.UNRELATED"));
        registry.add("runtime-lite.outbox.max-attempts", () -> 2);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("ALTER TABLE " + tables.outbox() + " DROP CONSTRAINT IF EXISTS reject_sent");
        jdbc.execute("ALTER TABLE " + tables.outbox() + " DROP CONSTRAINT IF EXISTS reject_result");
        jdbc.execute("ALTER TABLE " + tables.enrichment() + " DROP CONSTRAINT IF EXISTS reject_reply");
        jdbc.execute("ALTER TABLE " + tables.enrichment() + " DROP CONSTRAINT IF EXISTS reject_finish");
        jdbc.execute("TRUNCATE " + tables.enrichment() + ", " + tables.inboundMessages() + ", " + tables.outbox());
        sender.calls.set(0);
        sender.messages.clear();
        sender.fail = false;
    }

    @AfterAll
    void cleanup() {
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test
    void rollsBackAndDeduplicatesDirectMappingWithoutLookup() {
        var input = new InboundMessage("input-without-lookup", "", Map.of("origin", "synthetic"));
        jdbc.execute("ALTER TABLE " + tables.outbox()
            + " ADD CONSTRAINT reject_result CHECK (jms_reply_to IS NOT NULL)");

        assertThatThrownBy(() -> processing.process("workflow", input))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.inboundMessages(), Integer.class)).isZero();
        assertThat(countOutbox()).isZero();

        jdbc.execute("ALTER TABLE " + tables.outbox() + " DROP CONSTRAINT reject_result");
        processing.process("workflow", input);
        processing.process("workflow", input);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.inboundMessages(), Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
            SELECT source_destination, processor_id, body, body_xml::text AS body_xml, headers ->> 'origin' AS origin
            FROM %s WHERE route_id = ? AND message_id = ?
            """.formatted(tables.inboundMessages()), "workflow", input.id()))
            .containsEntry("source_destination", "TEST.INPUT")
            .containsEntry("processor_id", "workflowProcessor")
            .containsEntry("body", input.body())
            .containsEntry("body_xml", null)
            .containsEntry("origin", "synthetic");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.enrichment(), Integer.class)).isZero();
        assertThat(countOutbox()).isEqualTo(1);
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(sender.messages).singleElement().satisfies(message -> {
            assertThat(message.message().destination()).isEqualTo(OUTPUT_QUEUE);
            assertThat(message.message().body()).isEqualTo(DIRECT_RESULT);
            assertThat(message.replyQueue()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dispatchTimeoutCancelsRequestAndRoutesOriginal(boolean expiredByWorker) {
        start();
        jdbc.update("UPDATE " + tables.enrichment() + " SET dispatch_deadline = clock_timestamp() - interval '2 seconds'");

        if (expiredByWorker) {
            assertThat(worker.expireBatch()).isEqualTo(1);
        }
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(sender.calls.get()).isZero();
        assertThat(workflow("status")).isEqualTo("READY_TO_MAP");
        assertThat(workflow("failure_code")).isEqualTo("DISPATCH_TIMEOUT");
        assertThat(outbox("status")).isEqualTo("CANCELLED");
        assertThat(outbox("last_error")).isEqualTo("DISPATCH_TIMEOUT");
        assertThat(worker.resumeBatch()).isEqualTo(1);
        assertFailureResult(EnrichmentFailure.DISPATCH_TIMEOUT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replyTimeoutRoutesOriginalAndIgnoresLateReplies(boolean expiredByWorker) throws Exception {
        start();
        dispatcher.dispatchBatch();
        assertThat(workflow("status")).isEqualTo("WAITING_REPLY");
        assertThat(jdbc.queryForObject("SELECT w.sent_at = o.sent_at FROM " + tables.enrichment()
            + " w JOIN " + tables.outbox() + " o ON o.id = w.request_outbox_id", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT reply_deadline - sent_at = interval '20 seconds' FROM "
            + tables.enrichment(), Boolean.class)).isTrue();
        expireReplyDeadline();

        if (expiredByWorker) {
            assertThat(worker.expireBatch()).isEqualTo(1);
        }
        reply("accepted");
        assertThat(workflow("status")).isEqualTo("READY_TO_MAP");
        assertThat(workflow("failure_code")).isEqualTo("REPLY_TIMEOUT");
        assertThat(countOutbox()).isEqualTo(1);

        assertThat(worker.resumeBatch()).isEqualTo(1);
        reply("accepted");
        assertThat(worker.resumeBatch()).isZero();
        assertFailureResult(EnrichmentFailure.REPLY_TIMEOUT);
    }

    @Test
    void replyAfterDatabaseSendRollbackCancelsRetryAndCompletesOnce() throws Exception {
        start();
        jdbc.execute("ALTER TABLE " + tables.outbox() + " ADD CONSTRAINT reject_sent CHECK (status <> 'SENT')");
        assertThatThrownBy(dispatcher::dispatchBatch).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(sender.calls.get()).isEqualTo(1);
        assertThat(workflow("status")).isEqualTo("PENDING_SEND");
        jdbc.execute("ALTER TABLE " + tables.outbox() + " DROP CONSTRAINT reject_sent");

        reply("accepted");
        reply("accepted");
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(outbox("status")).isEqualTo("CANCELLED");
        assertThat(sender.calls.get()).isEqualTo(1);
        assertThat(worker.resumeBatch()).isEqualTo(1);
        assertThat(worker.resumeBatch()).isZero();
        reply("accepted");
        assertThat(workflow("status")).isEqualTo("COMPLETED");
        assertThat(countOutbox()).isEqualTo(2);
    }

    @Test
    void rejectedReplyRoutesOriginal() throws Exception {
        start();
        dispatcher.dispatchBatch();
        reply("rejected");

        assertThat(worker.resumeBatch()).isEqualTo(1);
        assertFailureResult(EnrichmentFailure.EXTERNAL_REJECTED);
    }

    @Test
    void retriesRequestWithStableIdentityAndFailsBothRecordsAtTheAttemptLimit() {
        start();
        sender.fail = true;

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(workflow("status")).isEqualTo("PENDING_SEND");
        assertThat(outbox("status")).isEqualTo("PENDING");
        jdbc.update("UPDATE " + tables.outbox() + " SET available_at = clock_timestamp()");
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);

        assertThat(workflow("status")).isEqualTo("READY_TO_MAP");
        assertThat(workflow("failure_code")).isEqualTo("DISPATCH_FAILED");
        assertThat(outbox("status")).isEqualTo("FAILED");
        assertThat(outbox("attempts")).isEqualTo(Integer.toString(outboxProperties.getMaxAttempts()));
        var attempts = List.copyOf(sender.messages);
        assertThat(attempts).hasSize(2);
        assertThat(attempts.getFirst().id()).isEqualTo(attempts.getLast().id());
        assertThat(attempts.getFirst().message()).isEqualTo(attempts.getLast().message());
        assertThat(worker.resumeBatch()).isEqualTo(1);
        assertFailureResult(EnrichmentFailure.DISPATCH_FAILED);
    }

    @Test
    void rollsBackFailedReplyAndResultWritesAndOnlyOneWorkerCompletes() throws Exception {
        start();
        dispatcher.dispatchBatch();
        jdbc.execute("ALTER TABLE " + tables.enrichment()
            + " ADD CONSTRAINT reject_reply CHECK (status <> 'READY_TO_MAP')");

        assertThatThrownBy(() -> reply("accepted")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(workflow("status")).isEqualTo("WAITING_REPLY");
        jdbc.execute("ALTER TABLE " + tables.enrichment() + " DROP CONSTRAINT reject_reply");
        reply("accepted");
        jdbc.execute("ALTER TABLE " + tables.outbox()
            + " ADD CONSTRAINT reject_result CHECK (jms_reply_to IS NOT NULL)");

        assertThatThrownBy(worker::resumeBatch).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(workflow("status")).isEqualTo("READY_TO_MAP");
        assertThat(countOutbox()).isEqualTo(1);
        jdbc.execute("ALTER TABLE " + tables.outbox() + " DROP CONSTRAINT reject_result");

        assertThat(resumeWithTwoWorkers()).isEqualTo(1);
        assertThat(workflow("status")).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT body FROM " + tables.inboundMessages(), String.class)).isEqualTo("input");
        assertThat(workflow("reply_body")).isNull();
        assertThat(countOutbox()).isEqualTo(2);
    }

    @Test
    void failedWorkflowCompletionRollsBackOutboxAndCanBeResumedOnce() throws Exception {
        start();
        dispatcher.dispatchBatch();
        expireReplyDeadline();
        assertThat(worker.expireBatch()).isEqualTo(1);
        jdbc.execute("ALTER TABLE " + tables.enrichment()
            + " ADD CONSTRAINT reject_finish CHECK (status <> 'FAILED')");

        assertThatThrownBy(worker::resumeBatch).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(workflow("status")).isEqualTo("READY_TO_MAP");
        assertThat(workflow("failure_code")).isEqualTo("REPLY_TIMEOUT");
        assertThat(workflow("result_outbox_id")).isNull();
        assertThat(countOutbox()).isEqualTo(1);
        jdbc.execute("ALTER TABLE " + tables.enrichment() + " DROP CONSTRAINT reject_finish");

        assertThat(resumeWithTwoWorkers()).isEqualTo(1);
        assertFailureResult(EnrichmentFailure.REPLY_TIMEOUT);
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(sender.messages).last().satisfies(delivered -> {
            assertThat(delivered.message().destination()).isEqualTo(ERROR_QUEUE);
            assertThat(delivered.message().body()).isEqualTo(INPUT.body());
            assertThat(delivered.message().headers()).containsAllEntriesOf(INPUT.headers())
                .containsEntry(FAILURE_HEADER, EnrichmentFailure.REPLY_TIMEOUT.name());
        });
    }

    @Test
    void wrongQueueCannotClaimReply() throws Exception {
        start();
        dispatcher.dispatchBatch();
        receiver.receive("TEST.UNRELATED", replyMessage("accepted"));
        assertThat(workflow("status")).isEqualTo("WAITING_REPLY");
        assertThat(worker.resumeBatch()).isZero();
        assertThat(countOutbox()).isEqualTo(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n", "malformed"})
    void missingBlankOrMalformedReplyRoutesOriginal(String body) throws Exception {
        start();
        dispatcher.dispatchBatch();
        reply(body);

        assertThat(workflow("status")).isEqualTo("READY_TO_MAP");
        assertThat(workflow("failure_code")).isEqualTo("INVALID_REPLY");
        reply("accepted");
        assertThat(worker.resumeBatch()).isEqualTo(1);
        assertFailureResult(EnrichmentFailure.INVALID_REPLY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "missing-value"})
    void invalidDecodedReplyRoutesOriginal(String body) throws Exception {
        start();
        dispatcher.dispatchBatch();
        reply(body);

        assertThat(workflow("status")).isEqualTo("READY_TO_MAP");
        assertThat(workflow("failure_code")).isNull();
        assertThat(worker.resumeBatch()).isEqualTo(1);
        assertFailureResult(EnrichmentFailure.INVALID_REPLY);
    }

    @Test
    void freshApplicationInstanceResumesPersistedWaitingDocument() throws Exception {
        start();
        dispatcher.dispatchBatch();
        assertThat(workflow("status")).isEqualTo("WAITING_REPLY");
        var arguments = new ArrayList<String>();
        properties((name, value) -> arguments.add("--" + name + "=" + value.get()));

        try (var instance = new SpringApplicationBuilder(RuntimeLiteApplication.class, Fixtures.class)
            .web(WebApplicationType.NONE).profiles("int-test").run(arguments.toArray(String[]::new))) {
            instance.getBean(EnrichmentReplyReceiver.class).receive(REPLY_QUEUE, replyMessage("accepted"));
            assertThat(instance.getBean(EnrichmentWorker.class).resumeBatch()).isEqualTo(1);
        }

        assertThat(workflow("status")).isEqualTo("COMPLETED");
        assertThat(countOutbox()).isEqualTo(2);
    }

    private void start() {
        processing.process("workflow", INPUT);
    }

    private void expireReplyDeadline() {
        jdbc.update("UPDATE " + tables.enrichment() + " SET reply_deadline = clock_timestamp() - interval '2 seconds'");
    }

    private int resumeWithTwoWorkers() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(worker::resumeBatch);
            var second = executor.submit(worker::resumeBatch);
            return first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS);
        }
    }

    private void assertFailureResult(EnrichmentFailure reason) {
        assertThat(workflow("status")).isEqualTo("FAILED");
        assertThat(workflow("failure_code")).isEqualTo(reason.name());
        assertThat(workflow("reply_body")).isNull();
        assertThat(countOutbox()).isEqualTo(2);
        assertThat(jdbc.queryForMap("""
            SELECT o.destination, o.body, o.message_key, o.headers ->> 'origin' AS origin,
                o.headers ->> CAST(? AS text) AS reason, o.jms_reply_to
            FROM %s w JOIN %s o ON o.id = w.result_outbox_id
            """.formatted(tables.enrichment(), tables.outbox()), FAILURE_HEADER))
            .containsEntry("destination", ERROR_QUEUE)
            .containsEntry("body", INPUT.body())
            .containsEntry("message_key", INPUT.id())
            .containsEntry("origin", INPUT.headers().get("origin"))
            .containsEntry("reason", reason.name())
            .containsEntry("jms_reply_to", null);
    }

    private void reply(String body) throws Exception {
        receiver.receive(REPLY_QUEUE, replyMessage(body));
    }

    private TextMessage replyMessage(String body) throws Exception {
        var message = mock(TextMessage.class);
        when(message.getJMSCorrelationID()).thenReturn(workflow("correlation_id"));
        when(message.getJMSMessageID()).thenReturn("reply-1");
        when(message.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        when(message.getText()).thenReturn(body);
        return message;
    }

    private String workflow(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + tables.enrichment(), String.class);
    }

    private String outbox(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + tables.outbox(), String.class);
    }

    private int countOutbox() {
        return jdbc.queryForObject("SELECT count(*) FROM " + tables.outbox(), Integer.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        LookupOperation lookupOperation() {
            return new LookupOperation();
        }

        @Bean
        EnrichingMessageProcessor<String> workflowProcessor(LookupOperation lookup) {
            return new EnrichingMessageProcessor<>() {
                @Override
                public JmsEnrichmentOperation<String> operation() {
                    return lookup;
                }

                @Override
                public Optional<MessageBody> prepareLookup(InboundMessage input) {
                    if (input.body().isBlank()) {
                        return Optional.empty();
                    }
                    return Optional.of(MessageBody.text(input.body()));
                }

                @Override
                public OutboundMessage map(InboundMessage input, EnrichmentResult<String> result) {
                    return switch (result) {
                        case EnrichmentResult.Skipped<String> ignored ->
                            OutboundMessage.of(Transport.JMS, OUTPUT_QUEUE, DIRECT_RESULT);
                        case EnrichmentResult.Success<String> success ->
                            OutboundMessage.of(Transport.JMS, OUTPUT_QUEUE, success.reply());
                        case EnrichmentResult.Failure<String> failure -> {
                            var headers = new HashMap<>(input.headers());
                            headers.put(FAILURE_HEADER, failure.reason().name());
                            yield new OutboundMessage(Transport.JMS, ERROR_QUEUE, input.body(), input.id(), headers);
                        }
                    };
                }
            };
        }

        @Bean
        @Primary
        RecordingSender recordingSender() {
            return new RecordingSender();
        }
    }

    static class LookupOperation implements JmsEnrichmentOperation<String> {

        @Override
        public String key() {
            return "lookup-v1";
        }

        @Override
        public MessageBody validateReply(InboundMessage reply) {
            if (reply.body().equals("malformed")) {
                throw new IllegalArgumentException("Malformed lookup reply");
            }
            return JmsEnrichmentOperation.super.validateReply(reply);
        }

        @Override
        public String decode(InboundMessage reply) {
            return switch (reply.body()) {
                case "accepted" -> reply.body();
                case "rejected" -> throw new ExternalEnrichmentRejectedException("rejected");
                case "missing-value" -> null;
                default -> throw new IllegalArgumentException("Unexpected lookup reply");
            };
        }
    }

    static class RecordingSender implements MessageSender {

        final AtomicInteger calls = new AtomicInteger();
        final ConcurrentLinkedQueue<OutboxMessage> messages = new ConcurrentLinkedQueue<>();
        boolean fail;

        @Override
        public void send(OutboxMessage message) throws IOException {
            calls.incrementAndGet();
            messages.add(message);
            if (fail) {
                throw new IOException("Synthetic transport failure");
            }
        }
    }
}
