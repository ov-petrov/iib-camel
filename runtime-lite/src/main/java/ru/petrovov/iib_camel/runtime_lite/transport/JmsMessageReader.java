package ru.petrovov.iib_camel.runtime_lite.transport;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import lombok.RequiredArgsConstructor;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.transport.config.ListenerProperties;

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

    private byte[] readBody(Message message) throws JMSException {
        if (message instanceof TextMessage text) {
            String value = text.getText();
            if (value == null) {
                throw new JMSException("Text message body is required");
            }
            requireSize(value.length());
            byte[] body = value.getBytes(StandardCharsets.UTF_8);
            requireSize(body.length);
            return body;
        }
        if (message instanceof BytesMessage bytes) {
            bytes.reset();
            long length = bytes.getBodyLength();
            requireSize(length);
            byte[] body = new byte[(int) length];
            byte[] buffer = new byte[Math.min(body.length, 8192)];
            int offset = 0;
            while (offset < body.length) {
                int count = bytes.readBytes(buffer, Math.min(buffer.length, body.length - offset));
                if (count <= 0) {
                    throw new JMSException("Incomplete bytes message body");
                }
                System.arraycopy(buffer, 0, body, offset, count);
                offset += count;
            }
            return body;
        }
        throw new JMSException("Only TextMessage and BytesMessage are supported");
    }

    private void requireSize(long size) throws JMSException {
        if (size < 0 || size > properties.getMaxBodyBytes()) {
            throw new JMSException("Message body exceeds the configured size limit");
        }
    }
}
