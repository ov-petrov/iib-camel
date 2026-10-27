package ru.petrovov.iib_camel.runtime_lite.transport;

import jakarta.jms.BytesMessage;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSProducer;
import jakarta.jms.JMSRuntimeException;
import jakarta.jms.Queue;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InterruptException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import ru.petrovov.iib_camel.runtime_lite.message.MessagePayload;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxMessage;
import ru.petrovov.iib_camel.runtime_lite.transport.config.KafkaProducerProperties;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrokerMessageSenderTest {

    private final ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
    private final KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
    private final BrokerMessageSender sender = new BrokerMessageSender(
        connectionFactory, kafkaTemplate, new KafkaProducerProperties());

    @Test
    void commitsPersistentJmsSendBeforeReturningAndProtectsDeliveryIdentity() throws Exception {
        var context = mock(JMSContext.class);
        var producer = mock(JMSProducer.class);
        var message = mock(BytesMessage.class);
        var destination = mock(Queue.class);
        when(connectionFactory.createContext(JMSContext.SESSION_TRANSACTED)).thenReturn(context);
        when(context.createBytesMessage()).thenReturn(message);
        when(context.createQueue("output")).thenReturn(destination);
        when(context.createProducer()).thenReturn(producer);
        when(producer.setDeliveryMode(DeliveryMode.PERSISTENT)).thenReturn(producer);
        var outgoing = message(Transport.JMS);

        sender.send(outgoing);

        verify(message).writeBytes(outgoing.payload().body());
        verify(message).setStringProperty(MessageHeaders.MESSAGE_ID, outgoing.id().toString());
        var order = inOrder(producer, context);
        order.verify(producer).send(destination, message);
        order.verify(context).commit();
        order.verify(context).close();

        doThrow(new JMSRuntimeException("commit failed")).when(context).commit();
        assertThatThrownBy(() -> sender.send(outgoing)).isInstanceOf(JMSRuntimeException.class);
    }

    @Test
    void waitsForKafkaAcknowledgmentAndKeepsStableIdentityOnRetries() throws Exception {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var outgoing = message(Transport.KAFKA);

        sender.send(outgoing);

        var captured = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captured.capture());
        ProducerRecord<String, byte[]> record = captured.getValue();
        assertThat(record.key()).isEqualTo("key");
        assertThat(record.value()).isEqualTo(outgoing.payload().body());
        assertThat(record.headers().headers(MessageHeaders.MESSAGE_ID)).hasSize(1);
        assertThat(new String(record.headers().lastHeader(MessageHeaders.MESSAGE_ID).value(), StandardCharsets.UTF_8))
            .isEqualTo(outgoing.id().toString());

        when(kafkaTemplate.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker rejected")));
        assertThatThrownBy(() -> sender.send(outgoing)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void preservesInterruptWhenWaitingForKafka() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> sender.send(message(Transport.KAFKA))).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void preservesInterruptWhenKafkaSendIsInterruptedBeforeReturningFuture() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
            .thenThrow(new InterruptException(new InterruptedException()));
        try {
            assertThatThrownBy(() -> sender.send(message(Transport.KAFKA))).isInstanceOf(InterruptException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private OutboxMessage message(Transport transport) {
        return new OutboxMessage(UUID.randomUUID(), transport, "output",
            new MessagePayload(new byte[]{1, 2, 3}, "key", Map.of(MessageHeaders.MESSAGE_ID, "untrusted")), 0);
    }
}
