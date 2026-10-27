package ru.petrovov.iib_camel.runtime_lite.transport.config;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import jakarta.jms.ConnectionFactory;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import static org.assertj.core.api.Assertions.assertThat;
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
}
