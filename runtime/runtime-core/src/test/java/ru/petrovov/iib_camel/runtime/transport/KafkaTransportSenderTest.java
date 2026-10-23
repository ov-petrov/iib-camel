package ru.petrovov.iib_camel.runtime.transport;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import ru.petrovov.iib_camel.runtime.message.MessageHeader;
import ru.petrovov.iib_camel.runtime.message.OutboundMessage;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KafkaTransportSenderTest {
    @Test void preservesPayloadKeyDuplicateHeadersAndStableEventId() throws Exception {
        var producer = new MockProducer<byte[], byte[]>(true, null, new ByteArraySerializer(), new ByteArraySerializer());
        UUID eventId = UUID.randomUUID();
        try (var sender = new KafkaTransportSender(() -> producer, Duration.ofSeconds(1))) {
            sender.send(new OutboundMessage(eventId, "kafka", "synthetic-topic", new byte[]{0, 1, 2},
                    List.of(MessageHeader.text("label", "one"), MessageHeader.text("label", "two")), "partition-key"));
            var record = producer.history().getFirst();
            assertThat(record.value()).containsExactly(0, 1, 2);
            assertThat(new String(record.key(), StandardCharsets.UTF_8)).isEqualTo("partition-key");
            assertThat(record.headers().headers("label")).hasSize(2);
            assertThat(new String(record.headers().lastHeader("applicationEventId").value(), StandardCharsets.UTF_8)).isEqualTo(eventId.toString());
        }
        assertThat(producer.closed()).isTrue();
    }

    @Test void waitsForAcknowledgementAndPropagatesTimeout() throws Exception {
        @SuppressWarnings("unchecked") Producer<byte[], byte[]> producer = mock(Producer.class);
        when(producer.send(any())).thenReturn(new CompletableFuture<>());
        try (var sender = new KafkaTransportSender(() -> producer, Duration.ofMillis(10))) {
            assertThatThrownBy(() -> sender.send(message())).isInstanceOf(TimeoutException.class);
        }
        verify(producer).close(Duration.ofMillis(10));
    }

    @Test void doesNotCreateAProducerUntilFirstSendAndCannotReopenAfterClose() {
        @SuppressWarnings("unchecked") java.util.function.Supplier<Producer<byte[], byte[]>> supplier = mock(java.util.function.Supplier.class);
        var sender = new KafkaTransportSender(supplier, Duration.ofSeconds(1));
        sender.close();
        assertThatThrownBy(() -> sender.send(message())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(supplier);
    }

    private static OutboundMessage message() {
        return new OutboundMessage(UUID.randomUUID(), "kafka", "synthetic-topic", new byte[]{1}, List.of(), null);
    }
}
