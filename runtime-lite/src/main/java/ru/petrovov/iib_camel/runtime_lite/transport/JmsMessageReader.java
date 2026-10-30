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
            throw new JMSException("Only TextMessage is supported");
        }
        String body = text.getText();
        if (body == null) {
            throw new JMSException("Text message body is required");
        }
        requireSize(body.length());
        requireSize(body.getBytes(StandardCharsets.UTF_8).length);
        return body;
    }

    private void requireSize(long size) throws JMSException {
        if (size < 0 || size > properties.getMaxBodyBytes()) {
            throw new JMSException("Message body exceeds the configured size limit");
        }
    }
}
