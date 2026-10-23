package ru.petrovov.iib_camel.runtime.transport;

import jakarta.jms.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jms.connection.JmsTransactionManager;
import org.springframework.jms.core.JmsTemplate;
import ru.petrovov.iib_camel.runtime.message.MessageHeader;
import ru.petrovov.iib_camel.runtime.message.OutboundMessage;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JmsTransportSenderTest {
    private final ConnectionFactory factory = mock(ConnectionFactory.class);
    private final Connection connection = mock(Connection.class);
    private final Session session = mock(Session.class);
    private final Queue destination = mock(Queue.class);
    private final MessageProducer producer = mock(MessageProducer.class);
    private final BytesMessage message = mock(BytesMessage.class);
    private JmsTransportSender sender;

    @BeforeEach
    void configure() throws Exception {
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createSession(true, Session.AUTO_ACKNOWLEDGE)).thenReturn(session);
        when(session.getTransacted()).thenReturn(true);
        when(session.createQueue("synthetic-output")).thenReturn(destination);
        when(session.createProducer(destination)).thenReturn(producer);
        when(session.createBytesMessage()).thenReturn(message);
        sender = new JmsTransportSender(
            "artemis", new JmsTemplate(factory), new JmsTransactionManager(factory));
    }

    @Test
    void sendsBinaryPersistentTypedMessageAndCommitsBeforeReturning() throws Exception {
        UUID eventId = UUID.randomUUID();
        var headers = List.of(
            MessageHeader.text("label", "hello"),
            new MessageHeader("active", "BOOLEAN", new byte[]{1}),
                new MessageHeader("count", "INT", ByteBuffer.allocate(4).putInt(42).array()),
                new MessageHeader("JMSCorrelationID", "BYTES", new byte[]{5, 6}));
        sender.send(new OutboundMessage(
            eventId, "artemis", "synthetic-output", new byte[]{1, 2, 3}, headers, null));
        verify(message).writeBytes(new byte[]{1, 2, 3});
        verify(message).setStringProperty("applicationEventId", eventId.toString());
        verify(message).setObjectProperty("label", "hello");
        verify(message).setObjectProperty("active", true);
        verify(message).setObjectProperty("count", 42);
        verify(message).setJMSCorrelationIDAsBytes(new byte[]{5, 6});
        var order = inOrder(producer, session);
        order.verify(producer).send(
            message,
            DeliveryMode.PERSISTENT,
            Message.DEFAULT_PRIORITY,
            Message.DEFAULT_TIME_TO_LIVE);
        order.verify(session).commit();
        verify(session, never()).rollback();
    }

    @Test
    void rollsBackAndReportsProviderFailure() throws Exception {
        doThrow(new JMSException("synthetic send failure"))
            .when(producer)
            .send(eq(message), anyInt(), anyInt(), anyLong());
        assertThatThrownBy(() -> sender.send(outbound(List.of()))).isInstanceOf(RuntimeException.class);
        verify(session).rollback();
        verify(session, never()).commit();
    }

    @Test
    void rejectsUnsupportedPropertiesBeforeOpeningAConnection() {
        for (List<MessageHeader> headers : List.of(
                List.of(new MessageHeader("opaque", "BYTES", new byte[]{1})),
                List.of(MessageHeader.text("duplicate", "one"), MessageHeader.text("duplicate", "two")),
                List.of(MessageHeader.text("applicationEventId", "spoof")),
                List.of(MessageHeader.text("JMSPriority", "4")))) {
            assertThatThrownBy(() -> sender.send(outbound(headers)))
                .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(factory);
    }

    private static OutboundMessage outbound(List<MessageHeader> headers) {
        return new OutboundMessage(
            UUID.randomUUID(), "artemis", "synthetic-output", new byte[]{1}, headers, null);
    }
}
