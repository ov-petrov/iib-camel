package ru.petrovov.iib_camel.runtime_lite.transport;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import lombok.RequiredArgsConstructor;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.config.transport.ListenerProperties;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

@RequiredArgsConstructor
public class JmsMessageReader {

    private final ListenerProperties properties;

    public InboundMessage read(Message message) throws JMSException {
        var headers = new LinkedHashMap<String, String>();
        var names = message.getPropertyNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement().toString();
            if (!name.startsWith("JMS") && message.getObjectProperty(name) instanceof String value) {
                headers.put(name, value);
            }
        }
        String correlationId = message.getJMSCorrelationID();
        if (correlationId != null) {
            headers.put(MessageHeaders.CORRELATION_ID, correlationId);
        }
        return new InboundMessage(message.getJMSMessageID(), readBody(message), headers);
    }

    private String readBody(Message message) throws JMSException {
        if (!(message instanceof TextMessage text)) {
            throw new InvalidMessageException("Only TextMessage is supported");
        }
        String body = text.getText();
        if (body == null) {
            throw new InvalidMessageException("Text message body is required");
        }
        if (!isBodyWithinLimit(body)) {
            throw new InvalidMessageException("Message body exceeds the configured size limit");
        }
        return body;
    }

    public boolean isBodyWithinLimit(String body) {
        int limit = properties.getMaxBodyBytes();
        return body != null && body.length() <= limit && body.getBytes(StandardCharsets.UTF_8).length <= limit;
    }

    public static class InvalidMessageException extends JMSException {

        InvalidMessageException(String message) {
            super(message);
        }
    }
}
