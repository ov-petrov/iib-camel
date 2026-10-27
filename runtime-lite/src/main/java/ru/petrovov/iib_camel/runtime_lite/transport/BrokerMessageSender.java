package ru.petrovov.iib_camel.runtime_lite.transport;

import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InterruptException;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxMessage;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class BrokerMessageSender implements MessageSender {

    private final BrokerRegistry brokers;

    @Override
    public void send(OutboxMessage message) throws Exception {
        switch (message.transport()) {
            case JMS -> sendJms(message);
            case KAFKA -> sendKafka(message);
        }
    }

    private void sendJms(OutboxMessage outgoing) throws JMSException {
        try (var context = brokers.requireJms(outgoing.broker()).createContext(JMSContext.SESSION_TRANSACTED)) {
            var message = context.createBytesMessage();
            message.writeBytes(outgoing.payload().body());
            for (var header : outgoing.payload().headers().entrySet()) {
                if (MessageHeaders.CORRELATION_ID.equals(header.getKey())) {
                    message.setJMSCorrelationID(header.getValue());
                } else if (!MessageHeaders.MESSAGE_ID.equals(header.getKey())) {
                    message.setStringProperty(header.getKey(), header.getValue());
                }
            }
            message.setStringProperty(MessageHeaders.MESSAGE_ID, outgoing.id().toString());
            context.createProducer()
                .setDeliveryMode(DeliveryMode.PERSISTENT)
                .send(context.createQueue(outgoing.destination()), message);
            context.commit();
        }
    }

    private void sendKafka(OutboxMessage outgoing) throws Exception {
        var payload = outgoing.payload();
        var record = new ProducerRecord<String, byte[]>(outgoing.destination(), payload.key(), payload.body());
        payload.headers().forEach((name, value) -> {
            if (!MessageHeaders.MESSAGE_ID.equals(name)) {
                record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
            }
        });
        record.headers().add(MessageHeaders.MESSAGE_ID,
            outgoing.id().toString().getBytes(StandardCharsets.UTF_8));
        try {
            var broker = brokers.requireKafka(outgoing.broker());
            broker.template().send(record).get(broker.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (InterruptException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }
}
