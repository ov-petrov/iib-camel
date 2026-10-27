package ru.petrovov.iib_camel.runtime_lite.transport;

import jakarta.jms.ConnectionFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class BrokerRegistry implements DisposableBean {

    private final Map<String, ConnectionFactory> jmsBrokers;
    private final Map<String, DefaultJmsListenerContainerFactory> listenerFactories;
    private final Map<String, KafkaBroker> kafkaBrokers;
    private final List<DefaultKafkaProducerFactory<String, byte[]>> ownedProducerFactories;

    public BrokerRegistry(Map<String, ConnectionFactory> jmsBrokers,
                          Map<String, DefaultJmsListenerContainerFactory> listenerFactories,
                          Map<String, KafkaBroker> kafkaBrokers,
                          List<DefaultKafkaProducerFactory<String, byte[]>> ownedProducerFactories) {
        this.jmsBrokers = Map.copyOf(jmsBrokers);
        this.listenerFactories = Map.copyOf(listenerFactories);
        this.kafkaBrokers = Map.copyOf(kafkaBrokers);
        this.ownedProducerFactories = List.copyOf(ownedProducerFactories);
    }

    public ConnectionFactory requireJms(String broker) {
        return require(jmsBrokers, broker, "JMS");
    }

    public DefaultJmsListenerContainerFactory listenerFactory(String broker) {
        return require(listenerFactories, broker, "JMS");
    }

    public KafkaBroker requireKafka(String broker) {
        return require(kafkaBrokers, broker, "Kafka");
    }

    @Override
    public void destroy() {
        RuntimeException failure = null;
        for (var factory : ownedProducerFactories) {
            try {
                factory.destroy();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static <T> T require(Map<String, T> brokers, String id, String type) {
        T broker = brokers.get(id);
        if (broker == null) {
            throw new IllegalArgumentException("Unknown " + type + " broker: " + id);
        }
        return broker;
    }

    public record KafkaBroker(KafkaTemplate<String, byte[]> template, Duration timeout) {
    }
}
