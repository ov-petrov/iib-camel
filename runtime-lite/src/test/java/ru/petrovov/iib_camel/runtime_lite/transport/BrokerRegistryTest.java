package ru.petrovov.iib_camel.runtime_lite.transport;

import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BrokerRegistryTest {

    @Test
    void resolvesOnlyTheRequestedKindAndNeverFallsBack() {
        var jms = mock(ConnectionFactory.class);
        var listener = new DefaultJmsListenerContainerFactory();
        var kafka = mock(KafkaTemplate.class);
        var registry = new BrokerRegistry(Map.of("mq-a", jms), Map.of("mq-a", listener),
            Map.of("kafka-a", new BrokerRegistry.KafkaBroker(kafka, Duration.ofSeconds(5))), List.of());

        assertThat(registry.requireJms("mq-a")).isSameAs(jms);
        assertThat(registry.listenerFactory("mq-a")).isSameAs(listener);
        assertThat(registry.requireKafka("kafka-a").template()).isSameAs(kafka);
        assertThatThrownBy(() -> registry.requireJms("kafka-a"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown JMS broker");
        assertThatThrownBy(() -> registry.requireKafka("mq-a"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown Kafka broker");
        assertThatThrownBy(() -> registry.listenerFactory("missing"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown JMS broker");
    }

    @Test
    void closesEveryOwnedProducerEvenWhenOneCloseFails() {
        var first = mock(DefaultKafkaProducerFactory.class);
        var second = mock(DefaultKafkaProducerFactory.class);
        doThrow(new IllegalStateException("close failure")).when(first).destroy();
        var registry = new BrokerRegistry(Map.of(), Map.of(), Map.of(), List.of(first, second));

        assertThatThrownBy(registry::destroy).isInstanceOf(IllegalStateException.class);
        verify(first).destroy();
        verify(second).destroy();
    }
}
