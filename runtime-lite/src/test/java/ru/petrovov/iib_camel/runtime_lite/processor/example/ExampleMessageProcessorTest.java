package ru.petrovov.iib_camel.runtime_lite.processor.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.outbox.MessagePublisher;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;
import ru.petrovov.iib_camel.runtime_lite.config.xml.XmlConfiguration;
import ru.petrovov.iib_camel.runtime_lite.xml.XmlService;
import ru.petrovov.iib_camel.runtime_lite.xml.schema.XmlSchemaException;
import ru.petrovov.iib_camel.runtime_lite.xml.schema.XmlSchemaRegistry;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ExampleMessageProcessorTest {

    private static final String SCHEMA = "classpath:schemas/example/payment.xsd";
    private static final Map<String, String> HEADERS = Map.of("correlation-id", "correlation-1");

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(XmlConfiguration.class);

    @Test
    void validatesAndMapsPaymentAgainstTheActualSchema() {
        context.run(result -> {
            var xml = result.getBean(XmlService.class);
            PaymentDocument payment = xml.readValidated(payment("12.50", true), SCHEMA, PaymentDocument.class);

            assertThat(payment.getDocumentId()).isEqualTo("doc-123");
            assertThat(payment.getAmount()).isEqualByComparingTo("12.50");
            assertThat(payment.getCurrency()).isEqualTo("USD");
            assertThat(payment.getPayer().getName()).isEqualTo("Alex");
            assertThat(payment.getPayer().getAccount()).isEqualTo("account-123");
            assertThat(payment.isSendNotification()).isTrue();

            String serialized = xml.writeValidated(payment, SCHEMA);
            PaymentDocument restored = xml.readValidated(serialized, SCHEMA, PaymentDocument.class);
            assertThat(restored.getDocumentId()).isEqualTo(payment.getDocumentId());
            assertThat(restored.getAmount()).isEqualByComparingTo(payment.getAmount());
            assertThat(restored.getPayer().getAccount()).isEqualTo(payment.getPayer().getAccount());
            assertThat(restored.isSendNotification()).isTrue();
        });
    }

    @Test
    void readsBooleanValuesAllowedByTheSchema() {
        context.run(result -> {
            var xml = result.getBean(XmlService.class);
            for (var entry : Map.of("true", true, "false", false).entrySet()) {
                String body = payment("12.50", true).replace("<sendNotification>true</sendNotification>",
                    "<sendNotification>" + entry.getKey() + "</sendNotification>");

                PaymentDocument payment = xml.readValidated(body, SCHEMA, PaymentDocument.class);

                assertThat(payment.isSendNotification()).isEqualTo(entry.getValue());
            }

            for (String value : new String[] {"1", "0"}) {
                String body = payment("12.50", true).replace("<sendNotification>true</sendNotification>",
                    "<sendNotification>" + value + "</sendNotification>");
                assertThatThrownBy(() -> xml.readValidated(body, SCHEMA, PaymentDocument.class))
                    .isInstanceOf(XmlSchemaException.class);
            }
        });
    }

    @Test
    void returnsOriginalXmlAndPublishesJsonNotification() {
        context.run(result -> {
            var publisher = mock(MessagePublisher.class);
            var mapper = result.getBean(JsonMapper.class);
            var processor = processor(result.getBean(XmlService.class), result.getBean(XmlSchemaRegistry.class),
                publisher, mapper);
            String body = payment("12.50", true);

            OutboundMessage main = processor.process(new InboundMessage("message-1", body, HEADERS));

            assertThat(main.transport()).isEqualTo(Transport.KAFKA);
            assertThat(main.destination()).isEqualTo("main-topic");
            assertThat(main.body()).isEqualTo(body);
            assertThat(main.key()).isEqualTo("doc-123");
            assertThat(main.headers()).isEqualTo(HEADERS);

            var sent = org.mockito.ArgumentCaptor.forClass(OutboundMessage.class);
            verify(publisher).send(sent.capture());
            verifyNoMoreInteractions(publisher);
            OutboundMessage notification = sent.getValue();
            assertThat(notification.transport()).isEqualTo(Transport.KAFKA);
            assertThat(notification.destination()).isEqualTo("mail-topic");
            assertThat(notification.headers()).isEqualTo(HEADERS);
            var json = mapper.readTree(notification.body());
            assertThat(json.get("documentId").asString()).isEqualTo("doc-123");
            assertThat(new BigDecimal(json.get("amount").asString())).isEqualByComparingTo("12.50");
            assertThat(json.get("currency").asString()).isEqualTo("USD");
            assertThat(json.get("payerName").asString()).isEqualTo("Alex");
        });
    }

    @Test
    void doesNotPublishNotificationWhenDisabled() {
        context.run(result -> {
            var publisher = mock(MessagePublisher.class);
            var processor = processor(result.getBean(XmlService.class), result.getBean(XmlSchemaRegistry.class),
                publisher, result.getBean(JsonMapper.class));
            String body = payment("1.00", false);

            OutboundMessage main = processor.process(new InboundMessage("message-1", body, HEADERS));

            assertThat(main.destination()).isEqualTo("main-topic");
            assertThat(main.body()).isEqualTo(body);
            assertThat(main.key()).isEqualTo("doc-123");
            verifyNoInteractions(publisher);
        });
    }

    @Test
    void routesInvalidXmlToErrorWithoutPuttingDetailsInErrorCode() {
        context.run(result -> {
            var publisher = mock(MessagePublisher.class);
            var processor = processor(result.getBean(XmlService.class), result.getBean(XmlSchemaRegistry.class),
                publisher, result.getBean(JsonMapper.class));
            String secret = "synthetic-secret-value";
            String malformed = "<PaymentDocument><documentId>" + secret + "</PaymentDocument>";
            String wrongSchema = payment("1.00", true).replace("<currency>USD</currency>",
                "<currency>" + secret + "</currency>");
            String wrongCurrencyLength = payment("1.00", true).replace("<currency>USD</currency>",
                "<currency>USDA</currency>");

            for (String body : new String[] {malformed, wrongSchema, wrongCurrencyLength}) {
                OutboundMessage error = processor.process(new InboundMessage("message-1", body, HEADERS));
                assertError(error, body, "INVALID_XML");
                assertThat(error.headers().get("example_error_code")).doesNotContain(secret);
            }
            verifyNoInteractions(publisher);
        });
    }

    @Test
    void routesZeroAndNegativeAmountsToErrorWithoutPublishing() {
        context.run(result -> {
            var publisher = mock(MessagePublisher.class);
            var processor = processor(result.getBean(XmlService.class), result.getBean(XmlSchemaRegistry.class),
                publisher, result.getBean(JsonMapper.class));

            for (String amount : new String[] {"0", "-0.01"}) {
                String body = payment(amount, true);
                OutboundMessage error = processor.process(new InboundMessage("message-1", body, HEADERS));
                assertError(error, body, "NON_POSITIVE_AMOUNT");
            }
            verifyNoInteractions(publisher);
        });
    }

    @Test
    void propagatesPublisherAndSchemaLoadingFailures() {
        context.run(result -> {
            var publisher = mock(MessagePublisher.class);
            var failure = new IllegalStateException("publisher unavailable");
            doThrow(failure).when(publisher).send(any());
            var processor = processor(result.getBean(XmlService.class), result.getBean(XmlSchemaRegistry.class),
                publisher, result.getBean(JsonMapper.class));
            var message = new InboundMessage("message-1", payment("1", true), HEADERS);

            assertThatThrownBy(() -> processor.process(message)).isSameAs(failure);

            var registry = mock(XmlSchemaRegistry.class);
            var schemaFailure = new IllegalStateException("schema unavailable");
            when(registry.get(SCHEMA)).thenThrow(schemaFailure);
            var unusedPublisher = mock(MessagePublisher.class);
            var broken = processor(result.getBean(XmlService.class), registry, unusedPublisher,
                result.getBean(JsonMapper.class));
            assertThatThrownBy(() -> broken.process(message)).isSameAs(schemaFailure);
            verifyNoInteractions(unusedPublisher);
        });
    }

    private static ExampleMessageProcessor processor(XmlService xml, XmlSchemaRegistry schemas,
                                                      MessagePublisher publisher, JsonMapper mapper) {
        var properties = new ExampleProperties();
        properties.setMainTopic("main-topic");
        properties.setMailTopic("mail-topic");
        properties.setErrorTopic("error-topic");
        return new ExampleMessageProcessor(xml, schemas, publisher, properties, mapper);
    }

    private static void assertError(OutboundMessage error, String body, String code) {
        assertThat(error.transport()).isEqualTo(Transport.KAFKA);
        assertThat(error.destination()).isEqualTo("error-topic");
        assertThat(error.body()).isEqualTo(body);
        assertThat(error.key()).isEqualTo("message-1");
        assertThat(error.headers()).containsAllEntriesOf(HEADERS).containsEntry("example_error_code", code);
    }

    private static String payment(String amount, boolean notification) {
        return """
            <PaymentDocument>
              <documentId>doc-123</documentId>
              <amount>%s</amount>
              <currency>USD</currency>
              <payer><name>Alex</name><account>account-123</account></payer>
              <sendNotification>%s</sendNotification>
            </PaymentDocument>
            """.formatted(amount, notification);
    }
}
