package ru.petrovov.iib_camel.runtime_lite.config.transport;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransportConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
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
    void allowsReplacingJmsProviderAndUsesConfiguredKafkaProducerProperties() {
        var alternateProvider = new ActiveMQConnectionFactory("tcp://localhost:61616");
        context.withBean(ConnectionFactory.class, () -> alternateProvider)
            .withPropertyValues("runtime-lite.jms.provider=ARTEMIS",
                "spring.kafka.bootstrap-servers=broker.example:9092",
                "spring.kafka.producer.acks=0",
                "spring.kafka.producer.properties[enable.idempotence]=false",
                "spring.kafka.producer.properties[delivery.timeout.ms]=45000",
                "spring.kafka.producer.properties[request.timeout.ms]=20000",
                "spring.kafka.producer.properties[max.block.ms]=15000",
                "spring.kafka.producer.properties[linger.ms]=12",
                "spring.kafka.producer.properties[client.id]=synthetic-producer")
            .run(result -> {
                assertThat(result).hasNotFailed().hasSingleBean(ConnectionFactory.class);
                assertThat(result.getBean(ConnectionFactory.class)).isSameAs(alternateProvider);
                var producerFactory = result.getBean(DefaultKafkaProducerFactory.class);
                assertThat(producerFactory.getConfigurationProperties())
                    .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, List.of("broker.example:9092"))
                    .containsEntry(ProducerConfig.ACKS_CONFIG, "0")
                    .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false")
                    .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "45000")
                    .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "20000")
                    .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, "15000")
                    .containsEntry(ProducerConfig.LINGER_MS_CONFIG, "12")
                    .containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "synthetic-producer");
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
    void appliesCommonKafkaSecurityPropertiesToAllClients() {
        context.withPropertyValues("spring.kafka.properties[security.protocol]=SASL_SSL",
                "spring.kafka.properties[sasl.mechanism]=PLAIN",
                "spring.kafka.properties[sasl.jaas.config]=synthetic-credential")
            .run(result -> {
                assertThat(result).hasNotFailed();
                var producer = result.getBean(DefaultKafkaProducerFactory.class).getConfigurationProperties();
                var consumer = result.getBean(ConsumerFactory.class).getConfigurationProperties();
                var admin = result.getBean(KafkaAdmin.class).getConfigurationProperties();
                for (var properties : List.of(producer, consumer, admin)) {
                    assertThat(properties).containsEntry("security.protocol", "SASL_SSL")
                        .containsEntry("sasl.mechanism", "PLAIN")
                        .containsEntry("sasl.jaas.config", "synthetic-credential");
                }
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
    void rejectsPartialCredentials() {
        context.withPropertyValues("runtime-lite.mq.username=sample-user")
            .run(result -> assertThat(result).hasFailed());
    }
}
