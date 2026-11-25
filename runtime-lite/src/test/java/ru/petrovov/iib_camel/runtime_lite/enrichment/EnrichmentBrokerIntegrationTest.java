package ru.petrovov.iib_camel.runtime_lite.enrichment;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.TextMessage;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
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
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichingMessageProcessor;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichmentResult;
import ru.petrovov.iib_camel.runtime_lite.processor.JmsEnrichmentOperation;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;
import ru.petrovov.iib_camel.runtime_lite.xml.XmlService;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = RuntimeLiteApplication.class)
@ActiveProfiles("int-test")
@Import(EnrichmentBrokerIntegrationTest.Fixtures.class)
@EnabledIfSystemProperty(named = "runtime-lite.integration-test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnrichmentBrokerIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "");
    private static final String SCHEMA = "lite_e2e_" + SUFFIX;
    private static final String INPUT_A = "LITE.E2E.A." + SUFFIX;
    private static final String INPUT_B = "LITE.E2E.B." + SUFFIX;
    private static final String REQUEST = "LITE.E2E.REQUEST." + SUFFIX;
    private static final String REPLY = "LITE.E2E.REPLY." + SUFFIX;
    private static final String OUTPUT = "LITE.E2E.OUTPUT." + SUFFIX;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private JmsListenerEndpointRegistry listeners;

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledTasks;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryTables tables;

    @Autowired
    private XmlService xml;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.liquibase.default-schema", () -> SCHEMA);
        registry.add("runtime-lite.routes.broker.input-queue", () -> INPUT_A);
        registry.add("runtime-lite.routes.broker.processor", () -> "firstProcessor");
        registry.add("runtime-lite.routes.second.input-queue", () -> INPUT_B);
        registry.add("runtime-lite.routes.second.processor", () -> "secondProcessor");
        registry.add("runtime-lite.enrichment.operations.lookup-v1.request-queue", () -> REQUEST);
        registry.add("runtime-lite.enrichment.operations.lookup-v1.reply-queue", () -> REPLY);
        registry.add("runtime-lite.enrichment.operations.lookup-v1.reply-timeout", () -> "45s");
        registry.add("runtime-lite.enrichment.poll-interval", () -> "100ms");
        registry.add("runtime-lite.enrichment.default-reply-timeout", () -> "30s");
        registry.add("runtime-lite.soap.plain-jms-destinations",
            () -> String.join(",", INPUT_A, INPUT_B, REQUEST, REPLY, OUTPUT));
    }

    @Test
    void correlatesReversedRepliesForTwoProcessorsAndIgnoresDuplicate() throws Exception {
        jdbc.execute("ALTER TABLE " + tables.enrichment()
            + " ADD CONSTRAINT require_xml_reply CHECK (status <> 'READY_TO_MAP' OR reply_xml IS NOT NULL)");
        String inputA = xml.write(new Input("alpha"));
        String inputB = xml.write(new Input("beta"));
        try (var context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE);
             var requests = context.createConsumer(context.createQueue(REQUEST));
             var output = context.createConsumer(context.createQueue(OUTPUT))) {
            listeners.start();
            context.createProducer().send(context.createQueue(INPUT_A), inputA);
            context.createProducer().send(context.createQueue(INPUT_B), inputB);

            var first = (TextMessage) requests.receive(15000);
            var second = (TextMessage) requests.receive(15000);
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(first.getJMSReplyTo()).isNotNull();
            assertThat(second.getJMSReplyTo()).isNotNull();
            assertThat(first.getJMSCorrelationID()).isNotBlank().isNotEqualTo(second.getJMSCorrelationID());
            assertThat(Set.of(xml.read(first.getText(), Request.class).getValue(),
                xml.read(second.getText(), Request.class).getValue())).containsExactlyInAnyOrder("alpha", "beta");

            reply(context, second);
            reply(context, first);
            reply(context, first);

            var resultA = (TextMessage) output.receive(15000);
            var resultB = (TextMessage) output.receive(15000);
            assertThat(resultA).isNotNull();
            assertThat(resultB).isNotNull();
            assertThat(Set.of(xml.read(resultA.getText(), Result.class), xml.read(resultB.getText(), Result.class)))
                .containsExactlyInAnyOrder(new Result("first", "alpha", "reply-alpha"),
                    new Result("second", "beta", "reply-beta"));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.enrichment() + " WHERE status = ?",
                    Integer.class, "COMPLETED")).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.outbox(), Integer.class)).isEqualTo(4);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.outbox()
                    + " WHERE body_xml IS DOCUMENT AND body IS NULL", Integer.class)).isEqualTo(4);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.enrichment()
                    + " WHERE reply_xml IS NOT NULL", Integer.class)).isZero();
                assertThat(jdbc.queryForList("SELECT reply_deadline - sent_at = interval '45 seconds' FROM "
                    + tables.enrichment(), Boolean.class)).hasSize(2).containsOnly(true);
            });
            assertThat(jdbc.queryForList("""
                SELECT route_id, source_destination, processor_id, body_xml::text AS body_xml
                FROM %s WHERE body_xml IS DOCUMENT AND body IS NULL
                """.formatted(tables.inboundMessages())))
                .extracting(row -> row.get("route_id"), row -> row.get("source_destination"),
                    row -> row.get("processor_id"), row -> row.get("body_xml"))
                .containsExactlyInAnyOrder(tuple("broker", INPUT_A, "firstProcessor", inputA),
                    tuple("second", INPUT_B, "secondProcessor", inputB));
            assertThat(output.receive(500)).isNull();
        }
    }

    private void reply(JMSContext context, TextMessage request) throws Exception {
        var value = xml.read(request.getText(), Request.class).getValue();
        var reply = context.createTextMessage(xml.write(new Reply("reply-" + value)));
        reply.setJMSCorrelationID(request.getJMSCorrelationID());
        context.createProducer().send(request.getJMSReplyTo(), reply);
    }

    @AfterAll
    void cleanup() {
        listeners.stop();
        scheduledTasks.destroy();
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        LookupOperation lookupOperation(XmlService xml) {
            return new LookupOperation(xml);
        }

        @Bean
        EnrichingMessageProcessor<String> firstProcessor(LookupOperation operation, XmlService xml) {
            return new TestProcessor("first", operation, xml);
        }

        @Bean
        EnrichingMessageProcessor<String> secondProcessor(LookupOperation operation, XmlService xml) {
            return new TestProcessor("second", operation, xml);
        }
    }

    record LookupOperation(XmlService xml) implements JmsEnrichmentOperation<String> {

        @Override
        public String key() {
            return "lookup-v1";
        }

        @Override
        public MessageBody validateReply(InboundMessage reply) {
            return MessageBody.xml(xml.validate(reply.body()));
        }

        @Override
        public String decode(InboundMessage reply) {
            return xml.read(reply.body(), Reply.class).getValue();
        }

        MessageBody requestBody(String value) {
            return MessageBody.xml(xml.writeDocument(new Request(value)));
        }
    }

    record TestProcessor(String name, LookupOperation operation, XmlService xml)
            implements EnrichingMessageProcessor<String> {

        @Override
        public Optional<MessageBody> prepareLookup(InboundMessage input) {
            var document = xml.read(input.body(), Input.class);
            return Optional.ofNullable(document.getValue()).map(operation::requestBody);
        }

        @Override
        public OutboundMessage map(InboundMessage input, EnrichmentResult<String> enrichment) {
            var document = xml.read(input.body(), Input.class);
            var label = switch (enrichment) {
                case EnrichmentResult.Skipped<String> ignored -> null;
                case EnrichmentResult.Success<String> success -> success.reply();
                case EnrichmentResult.Failure<String> failure -> throw new IllegalStateException(failure.reason().name());
            };
            var result = new Result(name, document.getValue(), label);
            return OutboundMessage.builder()
                .transport(Transport.JMS)
                .destination(OUTPUT)
                .payload(MessageBody.xml(xml.writeDocument(result)))
                .headers(Map.of())
                .build();
        }
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @XmlRootElement(name = "Input")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Input {

        private String value;
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @XmlRootElement(name = "Request")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Request {

        private String value;
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @XmlRootElement(name = "Reply")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Reply {

        private String value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlRootElement(name = "Result")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Result {

        private String processor;
        private String value;
        private String enrichment;
    }
}
