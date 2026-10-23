package ru.petrovov.iib_camel.runtime.transport;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import ru.petrovov.iib_camel.runtime.message.MessageHeader;
import ru.petrovov.iib_camel.runtime.message.OutboundMessage;

public final class KafkaTransportSender implements TransportSender, AutoCloseable {
    private final Supplier<Producer<byte[], byte[]>> producerFactory;
    private final Duration timeout;
    private Producer<byte[], byte[]> producer;
    private boolean closed;

    public KafkaTransportSender(Supplier<Producer<byte[], byte[]>> producerFactory, Duration timeout) {
        this.producerFactory = Objects.requireNonNull(producerFactory);
        this.timeout = Objects.requireNonNull(timeout);
    }

    @Override public String transport() { return "kafka"; }

    @Override public void send(OutboundMessage message) throws Exception {
        if (!transport().equals(message.transport())) throw new IllegalArgumentException("Wrong transport for Kafka sender");
        var headers = new RecordHeaders();
        for (MessageHeader header : message.headers()) {
            if (JmsTransportSender.EVENT_ID_HEADER.equals(header.name())) throw new IllegalArgumentException("applicationEventId is reserved");
            HeaderValueCodec.decode(header);
            headers.add(header.name(), header.value());
        }
        headers.add(JmsTransportSender.EVENT_ID_HEADER, message.id().toString().getBytes(StandardCharsets.UTF_8));
        byte[] key = message.key() == null ? null : message.key().getBytes(StandardCharsets.UTF_8);
        var record = new ProducerRecord<byte[], byte[]>(message.destination(), null, null, key, message.payload(), headers);
        producer().send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private synchronized Producer<byte[], byte[]> producer() {
        if (closed) throw new IllegalStateException("Kafka sender is closed");
        if (producer == null) producer = producerFactory.get();
        return producer;
    }

    @Override public synchronized void close() {
        closed = true;
        if (producer != null) producer.close(timeout);
    }
}
