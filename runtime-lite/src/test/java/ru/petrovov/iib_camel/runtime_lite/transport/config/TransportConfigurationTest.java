package ru.petrovov.iib_camel.runtime_lite.transport.config;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import jakarta.jms.ConnectionFactory;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import ru.petrovov.iib_camel.runtime_lite.transport.BrokerIds;
import ru.petrovov.iib_camel.runtime_lite.transport.BrokerRegistry;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TransportConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(TransportConfiguration.class);

    @Test
    void configuresIbmMqAndTransactionalListenersWithoutConnecting() {
        context.withPropertyValues("runtime-lite.listeners.auto-startup=false",
            "runtime-lite.mq.username=", "runtime-lite.mq.password=",
            "runtime-lite.mq.queue-manager=SYNTHETIC", "runtime-lite.mq.channel=SYNTHETIC.CHANNEL")
            .run(result -> {
                assertThat(result).hasNotFailed();
                var connectionFactory = (MQConnectionFactory) result.getBean(ConnectionFactory.class);
                assertThat(connectionFactory.getQueueManager()).isEqualTo("SYNTHETIC");
                var endpoint = new SimpleJmsListenerEndpoint();
                endpoint.setId("test");
                endpoint.setDestination("INPUT");
                endpoint.setMessageListener(message -> {});
                var factory = result.getBean("liteJmsListenerContainerFactory",
                    DefaultJmsListenerContainerFactory.class);
                var listener = factory.createListenerContainer(endpoint);

                assertThat(listener.isSessionTransacted()).isTrue();
                assertThat(listener.isAutoStartup()).isFalse();
                assertThat(listener.getConnectionFactory()).isSameAs(connectionFactory);
            });
    }

    @Test
    void allowsReplacingJmsProviderAndRequiresKafkaBrokerAcknowledgment() {
        var alternateProvider = mock(ConnectionFactory.class);
        context.withBean(ConnectionFactory.class, () -> alternateProvider)
            .withPropertyValues("runtime-lite.mq.enabled=false", "runtime-lite.kafka.properties.acks=0")
            .run(result -> {
                assertThat(result).hasNotFailed().hasSingleBean(ConnectionFactory.class);
                assertThat(result.getBean(ConnectionFactory.class)).isSameAs(alternateProvider);
                var producerFactory = result.getBean(DefaultKafkaProducerFactory.class);
                assertThat(producerFactory.getConfigurationProperties())
                    .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                    .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            });
    }

    @Test
    void rejectsPartialCredentialsAndInvalidKafkaTimeout() {
        context.withPropertyValues("runtime-lite.mq.username=sample-user")
            .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("runtime-lite.kafka.send-timeout=0ms")
            .run(result -> assertThat(result).hasFailed());
    }

    @Test
    void createsIndependentNamedBrokersAndTransactionalListenerFactories() {
        context.withPropertyValues(
                "runtime-lite.listeners.auto-startup=false",
                "runtime-lite.transports.mq.mq-a.host=first.example",
                "runtime-lite.transports.mq.mq-a.queue-manager=QM_FIRST",
                "runtime-lite.transports.mq.mq-a.channel=FIRST.CHANNEL",
                "runtime-lite.transports.mq.mq-b.host=second.example",
                "runtime-lite.transports.mq.mq-b.queue-manager=QM_SECOND",
                "runtime-lite.transports.mq.mq-b.channel=SECOND.CHANNEL",
                "runtime-lite.transports.kafka.kafka-a.bootstrap-servers=first.example:9092",
                "runtime-lite.transports.kafka.kafka-a.send-timeout=4s",
                "runtime-lite.transports.kafka.kafka-b.bootstrap-servers=second.example:9092",
                "runtime-lite.transports.kafka.kafka-b.send-timeout=7s")
            .run(result -> {
                assertThat(result).hasNotFailed();
                var registry = result.getBean(BrokerRegistry.class);
                var first = (MQConnectionFactory) registry.requireJms("mq-a");
                var second = (MQConnectionFactory) registry.requireJms("mq-b");
                assertThat(first.getHostName()).isEqualTo("first.example");
                assertThat(second.getHostName()).isEqualTo("second.example");
                assertThat(first).isNotSameAs(second).isNotSameAs(registry.requireJms(BrokerIds.DEFAULT_MQ));
                var endpoint = new SimpleJmsListenerEndpoint();
                endpoint.setId("test");
                endpoint.setDestination("INPUT");
                endpoint.setMessageListener(message -> {
                });
                var listener = registry.listenerFactory("mq-b").createListenerContainer(endpoint);
                assertThat(listener.isSessionTransacted()).isTrue();
                assertThat(listener.isAutoStartup()).isFalse();
                assertThat(listener.getConnectionFactory()).isSameAs(second);
                var firstKafka = registry.requireKafka("kafka-a");
                var secondKafka = registry.requireKafka("kafka-b");
                assertThat(firstKafka.template()).isNotSameAs(secondKafka.template());
                assertThat(firstKafka.timeout()).isEqualTo(Duration.ofSeconds(4));
                assertThat(secondKafka.timeout()).isEqualTo(Duration.ofSeconds(7));
                assertThat(registry.requireKafka(BrokerIds.DEFAULT_KAFKA).template()).isSameAs(
                    result.getBean("liteKafkaTemplate"));
                assertThatThrownBy(() -> registry.requireJms("kafka-a"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown JMS broker");
            });
    }

    @Test
    void rejectsReservedDuplicateAndInvalidNamedBrokerDefinitions() {
        context.withPropertyValues("runtime-lite.transports.mq.default-mq.host=first.example",
                "runtime-lite.transports.mq.default-mq.queue-manager=QM",
                "runtime-lite.transports.mq.default-mq.channel=CHANNEL")
            .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("runtime-lite.transports.mq.shared.host=first.example",
                "runtime-lite.transports.mq.shared.queue-manager=QM",
                "runtime-lite.transports.mq.shared.channel=CHANNEL",
                "runtime-lite.transports.kafka.shared.bootstrap-servers=second.example:9092")
            .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("runtime-lite.transports.mq.incomplete.port=1414")
            .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("runtime-lite.transports.kafka.incomplete.send-timeout=4s")
            .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("runtime-lite.transports.mq.secure.host=first.example",
                "runtime-lite.transports.mq.secure.queue-manager=QM",
                "runtime-lite.transports.mq.secure.channel=CHANNEL",
                "runtime-lite.transports.mq.secure.username=sample-user")
            .run(result -> assertThat(result).hasFailed());
    }
}
