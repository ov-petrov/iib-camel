package ru.petrovov.iib_camel.runtime_lite.transport;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
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
import ru.petrovov.iib_camel.runtime_lite.processor.MessageProcessor;
import ru.petrovov.iib_camel.runtime_lite.soap.SoapService;
import ru.petrovov.iib_camel.runtime_lite.xml.XmlService;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = RuntimeLiteApplication.class)
@ActiveProfiles("int-test")
@Import(SoapBrokerIntegrationTest.Fixtures.class)
@EnabledIfSystemProperty(named = "runtime-lite.integration-test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SoapBrokerIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "");
    private static final String SCHEMA = "soap_broker_" + SUFFIX;
    private static final String INPUT = "SAMPLE.SOAP.IN." + SUFFIX;
    private static final String OUTPUT = "SAMPLE.SOAP.OUT." + SUFFIX;

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
    private PayloadProcessor processor;

    @Autowired
    private SoapService soap;

    @Autowired
    private XmlService xml;

    @Autowired
    private JmsMessageReader reader;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.liquibase.default-schema", () -> SCHEMA);
        registry.add("runtime-lite.routes.soap.input-queue", () -> INPUT);
        registry.add("runtime-lite.routes.soap.processor", () -> "soapPayloadProcessor");
    }

    @Test
    void redeliversOriginalEnvelopeAndDeliversOneWrappedResult() throws Exception {
        try (var context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE);
             var output = context.createConsumer(context.createQueue(OUTPUT))) {
            String wire = soap.wrap(xml.write(new Item("synthetic")));
            var message = context.createTextMessage(wire);
            message.setStringProperty("origin", "synthetic");
            message.setJMSCorrelationID("synthetic-correlation");
            listeners.start();
            context.createProducer().send(context.createQueue(INPUT), message);

            var received = output.receive(30000);
            assertThat(received).isNotNull();
            var delivered = reader.read(received);
            assertThat(xml.read(soap.unwrap(delivered.body()), Item.class)).isEqualTo(new Item("SYNTHETIC"));
            assertThat(delivered.headers()).containsEntry("origin", "synthetic")
                .containsEntry(MessageHeaders.CORRELATION_ID, "synthetic-correlation");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM " + tables.outbox(), String.class)).isEqualTo("SENT"));
            assertThat(processor.attempts).hasValue(2);
            assertThat(jdbc.queryForMap("SELECT body, body_xml, payload_format FROM " + tables.inboundMessages()))
                .containsEntry("body", wire).containsEntry("body_xml", null).containsEntry("payload_format", "SOAP_11");
            assertThat(jdbc.queryForObject("SELECT body FROM " + tables.outbox(), String.class)).isEqualTo(delivered.body());
            assertThat(output.receive(300)).isNull();
        }
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
        PayloadProcessor soapPayloadProcessor(XmlService xml) {
            return new PayloadProcessor(xml);
        }
    }

    @RequiredArgsConstructor
    static class PayloadProcessor implements MessageProcessor {

        private final XmlService xml;
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public OutboundMessage process(InboundMessage message) {
            var item = xml.read(message.body(), Item.class);
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("Synthetic retry");
            }
            return OutboundMessage.of(Transport.JMS, OUTPUT,
                MessageBody.xml(xml.writeDocument(new Item(item.getValue().toUpperCase(Locale.ROOT)))),
                null, message.headers());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlRootElement(name = "Item")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Item {

        private String value;
    }
}
