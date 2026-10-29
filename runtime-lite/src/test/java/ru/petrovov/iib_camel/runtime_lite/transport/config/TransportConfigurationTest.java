package ru.petrovov.iib_camel.runtime_lite.transport.config;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import static org.assertj.core.api.Assertions.assertThat;

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
        var alternateProvider = new ActiveMQConnectionFactory("tcp://localhost:61616");
        context.withBean(ConnectionFactory.class, () -> alternateProvider)
            .withPropertyValues("runtime-lite.jms.provider=ARTEMIS", "runtime-lite.kafka.properties.acks=0")
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
    void selectsArtemisWithoutCreatingAnIbmFactory() {
        context.withPropertyValues("runtime-lite.jms.provider=ARTEMIS",
            "runtime-lite.artemis.broker-url=tcp://localhost:61617",
            "runtime-lite.artemis.username=sample-user", "runtime-lite.artemis.password=sample-password")
            .run(result -> {
                assertThat(result).hasNotFailed().hasSingleBean(ConnectionFactory.class);
                var factory = (ActiveMQConnectionFactory) result.getBean(ConnectionFactory.class);
                assertThat(factory.getUser()).isEqualTo("sample-user");
                assertThat(factory.getPassword()).isEqualTo("sample-password");
                assertThat(result).doesNotHaveBean(MQConnectionFactory.class);
            });
    }

    @Test
    void rejectsInvalidProviderAndArtemisSettings() {
        context.withPropertyValues("runtime-lite.jms.provider=unknown")
            .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("runtime-lite.jms.provider=ARTEMIS", "runtime-lite.artemis.username=sample-user")
            .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("runtime-lite.jms.provider=ARTEMIS", "runtime-lite.artemis.broker-url=")
            .run(result -> assertThat(result).hasFailed());
    }

    @Test
    void rejectsPartialCredentialsAndInvalidKafkaTimeout() {
        context.withPropertyValues("runtime-lite.mq.username=sample-user")
            .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("runtime-lite.kafka.send-timeout=0ms")
            .run(result -> assertThat(result).hasFailed());
    }
}
