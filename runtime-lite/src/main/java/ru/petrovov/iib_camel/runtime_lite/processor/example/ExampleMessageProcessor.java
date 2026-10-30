package ru.petrovov.iib_camel.runtime_lite.processor.example;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.outbox.MessagePublisher;
import ru.petrovov.iib_camel.runtime_lite.processor.MessageProcessor;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;
import ru.petrovov.iib_camel.runtime_lite.xml.XmlProcessingException;
import ru.petrovov.iib_camel.runtime_lite.xml.XmlService;
import ru.petrovov.iib_camel.runtime_lite.xml.schema.XmlSchemaException;
import ru.petrovov.iib_camel.runtime_lite.xml.schema.XmlSchemaRegistry;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.HashMap;

@Component("exampleProcessor")
@Profile("local")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ExampleMessageProcessor implements MessageProcessor {

    private static final String PAYMENT_SCHEMA = "classpath:schemas/example/payment.xsd";
    private static final String ERROR_CODE_HEADER = "example_error_code";

    private final XmlService xmlService;
    private final XmlSchemaRegistry schemas;
    private final MessagePublisher publisher;
    private final ExampleProperties properties;
    private final JsonMapper jsonMapper;

    @Override
    public OutboundMessage process(InboundMessage message) {
        schemas.get(PAYMENT_SCHEMA);

        PaymentDocument payment;
        try {
            payment = xmlService.readValidated(message.body(), PAYMENT_SCHEMA, PaymentDocument.class);
        } catch (XmlProcessingException | XmlSchemaException exception) {
            return errorMessage(message, ErrorCode.INVALID_XML);
        }

        if (payment.getAmount().signum() <= 0) {
            return errorMessage(message, ErrorCode.NON_POSITIVE_AMOUNT);
        }

        if (payment.isSendNotification()) {
            publisher.send(notification(message, payment));
        }

        return new OutboundMessage(Transport.KAFKA, properties.getMainTopic(), message.body(),
            payment.getDocumentId(), message.headers());
    }

    private OutboundMessage notification(InboundMessage message, PaymentDocument payment) {
        var notification = new PaymentNotification(payment.getDocumentId(), payment.getAmount(),
            payment.getCurrency(), payment.getPayer().getName());
        return new OutboundMessage(Transport.KAFKA, properties.getMailTopic(),
            jsonMapper.writeValueAsString(notification), payment.getDocumentId(), message.headers());
    }

    private OutboundMessage errorMessage(InboundMessage message, ErrorCode error) {
        var headers = new HashMap<>(message.headers());
        headers.put(ERROR_CODE_HEADER, error.name());
        return new OutboundMessage(Transport.KAFKA, properties.getErrorTopic(), message.body(), message.id(), headers);
    }

    private enum ErrorCode {
        INVALID_XML,
        NON_POSITIVE_AMOUNT
    }

    private record PaymentNotification(String documentId, BigDecimal amount, String currency, String payerName) {
    }
}
