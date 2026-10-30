package ru.petrovov.iib_camel.runtime_lite.transport;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InterruptException;
import org.springframework.kafka.core.KafkaTemplate;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxMessage;

import java.nio.charset.StandardCharsets;

@RequiredArgsConstructor
public class BrokerMessageSender implements MessageSender {

    private final ConnectionFactory connectionFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public void send(OutboxMessage message) throws Exception {
        switch (message.message().transport()) {
            case JMS -> sendJms(message);
            case KAFKA -> sendKafka(message);
        }
    }

    private void sendJms(OutboxMessage outgoing) throws JMSException {
        var outgoingMessage = outgoing.message();
        try (var context = connectionFactory.createContext(JMSContext.SESSION_TRANSACTED)) {
            var message = context.createTextMessage(outgoingMessage.body());
            for (var header : outgoingMessage.headers().entrySet()) {
                if (MessageHeaders.CORRELATION_ID.equals(header.getKey())) {
                    message.setJMSCorrelationID(header.getValue());
                } else if (!MessageHeaders.MESSAGE_ID.equals(header.getKey())) {
                    message.setStringProperty(header.getKey(), header.getValue());
                }
            }
            message.setStringProperty(MessageHeaders.MESSAGE_ID, outgoing.id().toString());
            context.createProducer()
                .setDeliveryMode(DeliveryMode.PERSISTENT)
                .send(context.createQueue(outgoingMessage.destination()), message);
            context.commit();
        }
    }

    private void sendKafka(OutboxMessage outgoing) throws Exception {
        var message = outgoing.message();
        var record = new ProducerRecord<String, String>(message.destination(), message.key(), message.body());
        message.headers().forEach((name, value) -> {
            if (!MessageHeaders.MESSAGE_ID.equals(name)) {
                record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
            }
        });
        record.headers().add(MessageHeaders.MESSAGE_ID,
            outgoing.id().toString().getBytes(StandardCharsets.UTF_8));
        try {
            kafkaTemplate.send(record).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (InterruptException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }
}
