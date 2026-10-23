package ru.petrovov.iib_camel.runtime.transport;

import java.util.HashSet;
import jakarta.jms.BytesMessage;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import org.springframework.jms.connection.JmsTransactionManager;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.petrovov.iib_camel.runtime.message.MessageHeader;
import ru.petrovov.iib_camel.runtime.message.OutboundMessage;

public final class JmsTransportSender implements TransportSender {
    public static final String EVENT_ID_HEADER = "applicationEventId";
    private final String transport;
    private final JmsTemplate template;
    private final TransactionTemplate transaction;

    public JmsTransportSender(String transport, JmsTemplate template, JmsTransactionManager transactionManager) {
        this.transport = transport;
        this.template = template;
        template.setSessionTransacted(true);
        template.setExplicitQosEnabled(true);
        template.setDeliveryMode(DeliveryMode.PERSISTENT);
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public String transport() { return transport; }

    @Override public void send(OutboundMessage outbound) {
        if (!transport.equals(outbound.transport())) throw new IllegalArgumentException("Wrong transport for JMS sender");
        validateHeaders(outbound);
        transaction.executeWithoutResult(status -> template.send(outbound.destination(), session -> {
            BytesMessage message = session.createBytesMessage();
            message.writeBytes(outbound.payload());
            message.setStringProperty(EVENT_ID_HEADER, outbound.id().toString());
            for (MessageHeader header : outbound.headers()) setHeader(message, header);
            return message;
        }));
    }

    private static void validateHeaders(OutboundMessage outbound) {
        var names = new HashSet<String>();
        for (MessageHeader header : outbound.headers()) {
            if (!names.add(header.name())) throw new IllegalArgumentException("JMS does not support duplicate header names");
            if (EVENT_ID_HEADER.equals(header.name())) throw new IllegalArgumentException("applicationEventId is reserved");
            Object value = HeaderValueCodec.decode(header);
            if ("JMSCorrelationID".equals(header.name())) {
                if (!(value instanceof String || value instanceof byte[])) {
                    throw new IllegalArgumentException("JMSCorrelationID requires STRING or BYTES");
                }
            } else {
                if (header.name().startsWith("JMS") || !identifier(header.name())) {
                    throw new IllegalArgumentException("Unsupported JMS application property name");
                }
                if (value instanceof byte[]) throw new IllegalArgumentException("JMS application properties cannot contain BYTES");
            }
        }
    }

    private static boolean identifier(String name) {
        if (!Character.isJavaIdentifierStart(name.charAt(0))) return false;
        for (int i = 1; i < name.length(); i++) if (!Character.isJavaIdentifierPart(name.charAt(i))) return false;
        return true;
    }

    private static void setHeader(BytesMessage message, MessageHeader header) throws JMSException {
        Object value = HeaderValueCodec.decode(header);
        if ("JMSCorrelationID".equals(header.name())) {
            if (value instanceof byte[] bytes) message.setJMSCorrelationIDAsBytes(bytes);
            else message.setJMSCorrelationID((String) value);
        } else message.setObjectProperty(header.name(), value);
    }
}
