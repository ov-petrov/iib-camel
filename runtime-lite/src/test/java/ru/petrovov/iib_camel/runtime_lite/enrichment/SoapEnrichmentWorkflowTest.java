package ru.petrovov.iib_camel.runtime_lite.enrichment;

import jakarta.jms.TextMessage;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.petrovov.iib_camel.runtime_lite.RuntimeLiteApplication;
import ru.petrovov.iib_camel.runtime_lite.config.soap.SoapProperties;
import ru.petrovov.iib_camel.runtime_lite.config.transport.ListenerProperties;
import ru.petrovov.iib_camel.runtime_lite.inbox.MessageProcessingService;
import ru.petrovov.iib_camel.runtime_lite.jdbc.DeliveryTables;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.MessageBody;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxDispatcher;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxMessage;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichingMessageProcessor;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichmentResult;
import ru.petrovov.iib_camel.runtime_lite.processor.JmsEnrichmentOperation;
import ru.petrovov.iib_camel.runtime_lite.transport.MessageSender;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;
import ru.petrovov.iib_camel.runtime_lite.xml.XmlService;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = RuntimeLiteApplication.class)
@ActiveProfiles("int-test")
@Import(SoapEnrichmentWorkflowTest.Fixtures.class)
@EnabledIfSystemProperty(named = "runtime-lite.integration-test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SoapEnrichmentWorkflowTest {

    private static final String SCHEMA = "soap_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String INPUT = "SOAP.TEST.INPUT";
    private static final String REQUEST = "SOAP.TEST.REQUEST";
    private static final String REPLY = "SOAP.TEST.REPLY";
    private static final String OUTPUT = "SOAP.TEST.OUTPUT";
    private static final String WIRE_INPUT = envelope("<input>alpha</input>");
    private static final String WIRE_REPLY = envelope("<reply>beta</reply>");

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
    private Observations observations;

    @Autowired
    private SoapProperties soapProperties;

    @Autowired
    private ListenerProperties listenerProperties;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.liquibase.default-schema", () -> SCHEMA);
        registry.add("runtime-lite.enrichment.scheduling-enabled", () -> false);
        registry.add("runtime-lite.outbox.scheduling-enabled", () -> false);
        registry.add("runtime-lite.routes.workflow.input-queue", () -> INPUT);
        registry.add("runtime-lite.routes.workflow.processor", () -> "soapWorkflowProcessor");
        registry.add("runtime-lite.enrichment.operations.soap-lookup.request-queue", () -> REQUEST);
        registry.add("runtime-lite.enrichment.operations.soap-lookup.reply-queue", () -> REPLY);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("ALTER TABLE " + tables.outbox() + " DROP CONSTRAINT IF EXISTS reject_result");
        jdbc.execute("TRUNCATE " + tables.enrichment() + ", " + tables.inboundMessages() + ", " + tables.outbox());
        sender.messages.clear();
        soapProperties.setPlainJmsDestinations(Set.of());
        listenerProperties.setMaxBodyBytes(10 * 1024 * 1024);
        observations.skip = false;
        observations.invalidOutput = false;
        observations.requestBody = "<request>alpha</request>";
        observations.prepare = null;
        observations.validated = null;
        observations.decoded = null;
        observations.mappedInput = null;
        observations.mappedReply = null;
    }

    @AfterAll
    void cleanup() {
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test
    void storesWireMessagesAndPresentsOnlyPayloadsToBusinessCallbacks() throws Exception {
        start();
        assertPayload(observations.prepare, "input", "alpha");
        assertThat(jdbc.queryForMap("SELECT body, body_xml, payload_format FROM " + tables.inboundMessages()))
            .containsEntry("body", WIRE_INPUT).containsEntry("body_xml", null).containsEntry("payload_format", "SOAP_11");
        assertThat(workflow("reply_payload_format")).isEqualTo("SOAP_11");
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(sender.messages).singleElement().satisfies(message -> {
            assertThat(message.message().destination()).isEqualTo(REQUEST);
            assertEnvelope(message.message().body(), "request", "alpha");
            assertThat(message.replyQueue()).isEqualTo(REPLY);
        });
        assertThat(jdbc.queryForObject("SELECT body_xml IS NULL FROM " + tables.outbox(), Boolean.class)).isTrue();

        reply(WIRE_REPLY);
        assertPayload(observations.validated, "reply", "beta");
        assertThat(workflow("reply_body")).isEqualTo(WIRE_REPLY);
        assertThat(workflow("reply_xml")).isNull();
        assertThat(worker.resumeBatch()).isEqualTo(1);
        assertPayload(observations.decoded, "reply", "beta");
        assertPayload(observations.mappedInput, "input", "alpha");
        assertPayload(observations.mappedReply, "reply", "beta");
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(sender.messages).last().satisfies(message -> {
            assertThat(message.message().destination()).isEqualTo(OUTPUT);
            assertEnvelope(message.message().body(), "result", "beta");
        });
    }

    @Test
    void snapshotsInboundAndReplyFormatsAcrossConfigurationChanges() throws Exception {
        start();
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        soapProperties.setPlainJmsDestinations(Set.of(INPUT, REPLY));
        reply(WIRE_REPLY);
        assertThat(workflow("reply_body")).isEqualTo(WIRE_REPLY);
        assertThat(worker.resumeBatch()).isEqualTo(1);
        assertPayload(observations.validated, "reply", "beta");
        assertPayload(observations.decoded, "reply", "beta");
        assertPayload(observations.mappedInput, "input", "alpha");
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(sender.messages).last().satisfies(message -> assertEnvelope(message.message().body(), "result", "beta"));
    }

    @Test
    void snapshotsPlainInboundAndReplyFormatsWhenSoapBecomesDefault() throws Exception {
        soapProperties.setPlainJmsDestinations(Set.of(INPUT, REQUEST, REPLY));
        processing.process("workflow", new InboundMessage("synthetic-1", "<input>alpha</input>", Map.of()));
        assertThat(workflow("reply_payload_format")).isEqualTo("PLAIN");
        assertThat(jdbc.queryForMap("SELECT body, body_xml::text AS body_xml, payload_format FROM "
            + tables.inboundMessages()))
            .containsEntry("body", null).containsEntry("body_xml", "<input>alpha</input>")
            .containsEntry("payload_format", "PLAIN");
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        soapProperties.setPlainJmsDestinations(Set.of());

        reply("<reply>beta</reply>");
        assertThat(workflow("reply_body")).isNull();
        assertThat(workflow("reply_xml::text")).isEqualTo("<reply>beta</reply>");
        assertThat(worker.resumeBatch()).isEqualTo(1);
        assertPayload(observations.validated, "reply", "beta");
        assertPayload(observations.decoded, "reply", "beta");
        assertPayload(observations.mappedInput, "input", "alpha");
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(sender.messages).last().satisfies(message -> assertEnvelope(message.message().body(), "result", "beta"));
    }

    @Test
    void timeoutMapsBareOriginalAndPersistsWrappedResultOnceAfterRollback() {
        start();
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        jdbc.update("UPDATE " + tables.enrichment() + " SET reply_deadline = clock_timestamp() - interval '2 seconds'");
        assertThat(worker.expireBatch()).isEqualTo(1);
        jdbc.execute("ALTER TABLE " + tables.outbox() + " ADD CONSTRAINT reject_result CHECK (jms_reply_to IS NOT NULL)");
        assertThatThrownBy(worker::resumeBatch).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(workflow("status")).isEqualTo("READY_TO_MAP");
        jdbc.execute("ALTER TABLE " + tables.outbox() + " DROP CONSTRAINT reject_result");
        assertThat(worker.resumeBatch()).isEqualTo(1);
        assertThat(worker.resumeBatch()).isZero();
        assertPayload(observations.mappedInput, "input", "alpha");
        assertThat(observations.mappedReply).isEqualTo("REPLY_TIMEOUT");
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(sender.messages).last().satisfies(message -> assertEnvelope(message.message().body(), "failure", "alpha"));
    }

    @Test
    void invalidMappedPayloadFailsWorkflowWithoutPersistingResult() throws Exception {
        start();
        dispatcher.dispatchBatch();
        reply(WIRE_REPLY);
        observations.invalidOutput = true;

        assertThat(worker.resumeBatch()).isEqualTo(1);
        assertThat(workflow("status")).isEqualTo("FAILED");
        assertThat(workflow("failure_code")).isEqualTo("MAPPING_FAILED");
        assertThat(workflow("result_outbox_id")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.outbox(), Integer.class)).isEqualTo(1);
        assertThat(dispatcher.dispatchBatch()).isZero();
    }

    @Test
    void sizeLimitAppliesToWrappedUtf8RequestAndRollsBackInitialTransaction() {
        String request = "<request>" + "я".repeat(45) + "</request>";
        int limit = 140;
        assertThat(WIRE_INPUT.getBytes(StandardCharsets.UTF_8).length).isLessThan(limit);
        assertThat(request.getBytes(StandardCharsets.UTF_8).length).isLessThan(limit);
        observations.requestBody = request;
        listenerProperties.setMaxBodyBytes(limit);

        assertThatThrownBy(this::start).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.inboundMessages(), Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.enrichment(), Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.outbox(), Integer.class)).isZero();
    }

    @Test
    void malformedSoapReplyIsInvalidWithoutCallingReplyCallbacks() throws Exception {
        start();
        dispatcher.dispatchBatch();
        reply("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>");

        assertThat(workflow("failure_code")).isEqualTo("INVALID_REPLY");
        assertThat(observations.validated).isNull();
        assertThat(worker.resumeBatch()).isEqualTo(1);
        assertThat(observations.decoded).isNull();
        assertPayload(observations.mappedInput, "input", "alpha");
        assertThat(observations.mappedReply).isEqualTo("INVALID_REPLY");
    }

    @Test
    void skippedLookupMapsBareInputAndSendsWrappedResult() {
        observations.skip = true;
        start();

        assertPayload(observations.prepare, "input", "alpha");
        assertPayload(observations.mappedInput, "input", "alpha");
        assertThat(observations.mappedReply).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.enrichment(), Integer.class)).isZero();
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(sender.messages).singleElement().satisfies(message ->
            assertEnvelope(message.message().body(), "skipped", "skipped"));
    }

    @Test
    void soapFaultIsAnInvalidReply() throws Exception {
        start();
        dispatcher.dispatchBatch();
        reply(envelope("<soap:Fault><faultcode>soap:Server</faultcode><faultstring>failure</faultstring></soap:Fault>"));
        assertThat(workflow("failure_code")).isEqualTo("INVALID_REPLY");
        assertThat(worker.resumeBatch()).isEqualTo(1);
        assertPayload(observations.mappedInput, "input", "alpha");
        assertThat(observations.mappedReply).isEqualTo("INVALID_REPLY");
    }

    private void start() {
        processing.process("workflow", new InboundMessage("synthetic-1", WIRE_INPUT, Map.of()));
    }

    private void reply(String body) throws Exception {
        var message = mock(TextMessage.class);
        when(message.getJMSCorrelationID()).thenReturn(workflow("correlation_id"));
        when(message.getJMSMessageID()).thenReturn("synthetic-reply");
        when(message.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        when(message.getText()).thenReturn(body);
        receiver.receive(REPLY, message);
    }

    private String workflow(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + tables.enrichment(), String.class);
    }

    private static String envelope(String body) {
        return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
            + body + "</soap:Body></soap:Envelope>";
    }

    private static void assertPayload(String payload, String root, String value) {
        assertThat(payload).isNotNull().contains("<" + root, value, "</" + root + ">").doesNotContain("Envelope", "Body>");
    }

    private static void assertEnvelope(String payload, String root, String value) {
        assertThat(payload).contains("Envelope", "http://schemas.xmlsoap.org/soap/envelope/", "<" + root, value);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        Observations observations() {
            return new Observations();
        }

        @Bean
        LookupOperation soapLookupOperation(XmlService xml, Observations observations) {
            return new LookupOperation(xml, observations);
        }

        @Bean
        EnrichingMessageProcessor<String> soapWorkflowProcessor(LookupOperation operation, XmlService xml,
                                                                 Observations observations) {
            return new EnrichingMessageProcessor<>() {
                @Override
                public JmsEnrichmentOperation<String> operation() {
                    return operation;
                }

                @Override
                public Optional<MessageBody> prepareLookup(InboundMessage input) {
                    observations.prepare = input.body();
                    assertPayload(input.body(), "input", "alpha");
                    if (observations.skip) {
                        return Optional.empty();
                    }
                    return Optional.of(MessageBody.xml(xml.validate(observations.requestBody)));
                }

                @Override
                public OutboundMessage map(InboundMessage input, EnrichmentResult<String> result) {
                    observations.mappedInput = input.body();
                    String output = switch (result) {
                        case EnrichmentResult.Success<String> success -> {
                            observations.mappedReply = success.reply();
                            yield "<result>beta</result>";
                        }
                        case EnrichmentResult.Failure<String> failure -> {
                            observations.mappedReply = failure.reason().name();
                            yield "<failure>alpha</failure>";
                        }
                        case EnrichmentResult.Skipped<String> ignored -> {
                            observations.mappedReply = "SKIPPED";
                            yield "<skipped>skipped</skipped>";
                        }
                    };
                    var body = observations.invalidOutput ? MessageBody.text("<broken>") : MessageBody.xml(xml.validate(output));
                    return OutboundMessage.of(Transport.JMS, OUTPUT, body, null, Map.of());
                }
            };
        }

        @Bean
        @Primary
        RecordingSender soapRecordingSender() {
            return new RecordingSender();
        }
    }

    record LookupOperation(XmlService xml, Observations observations) implements JmsEnrichmentOperation<String> {

        @Override
        public String key() {
            return "soap-lookup";
        }

        @Override
        public MessageBody validateReply(InboundMessage reply) {
            observations.validated = reply.body();
            assertPayload(reply.body(), "reply", "beta");
            return MessageBody.xml(xml.validate(reply.body()));
        }

        @Override
        public String decode(InboundMessage reply) {
            observations.decoded = reply.body();
            return reply.body();
        }
    }

    static class Observations {
        boolean skip;
        boolean invalidOutput;
        String requestBody;
        String prepare;
        String validated;
        String decoded;
        String mappedInput;
        String mappedReply;
    }

    static class RecordingSender implements MessageSender {
        final ConcurrentLinkedQueue<OutboxMessage> messages = new ConcurrentLinkedQueue<>();

        @Override
        public void send(OutboxMessage message) {
            messages.add(message);
        }
    }
}
