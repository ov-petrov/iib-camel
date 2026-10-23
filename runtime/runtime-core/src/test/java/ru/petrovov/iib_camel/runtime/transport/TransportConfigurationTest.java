package ru.petrovov.iib_camel.runtime.transport;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import java.util.Map;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.component.kafka.KafkaComponent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jms.connection.JmsTransactionManager;
import ru.petrovov.iib_camel.runtime.transport.config.ArtemisTransportProperties;
import ru.petrovov.iib_camel.runtime.transport.config.IbmMqTransportProperties;
import ru.petrovov.iib_camel.runtime.transport.config.KafkaTransportProperties;
import ru.petrovov.iib_camel.runtime.transport.config.RuntimeTransportAutoConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

class TransportConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RuntimeTransportAutoConfiguration.class))
            .withPropertyValues("iib.transports.artemis.enabled=false", "iib.transports.ibmmq.enabled=false", "iib.transports.kafka.enabled=false");

    @Test void transportsAreAbsentByDefaultAndInvalidDisabledSettingsAreIgnored() {
        assertThat(new ArtemisTransportProperties().isEnabled()).isFalse();
        assertThat(new IbmMqTransportProperties().isEnabled()).isFalse();
        assertThat(new KafkaTransportProperties().isEnabled()).isFalse();
        context.withPropertyValues("iib.transports.artemis.max-connections=-1", "iib.transports.ibmmq.port=-1",
                "iib.transports.kafka.send-timeout=-1s").run(result -> {
            assertThat(result).hasNotFailed().doesNotHaveBean(TransportSender.class)
                    .doesNotHaveBean(ArtemisTransportProperties.class).doesNotHaveBean(IbmMqTransportProperties.class)
                    .doesNotHaveBean(KafkaTransportProperties.class);
        });
    }

    @Test void allComponentsCoexistWithoutConnectingToBrokers() {
        context.withPropertyValues("iib.transports.artemis.enabled=true", "iib.transports.artemis.broker-url=tcp://localhost:61616",
                "iib.transports.ibmmq.enabled=true", "iib.transports.ibmmq.host=localhost",
                "iib.transports.ibmmq.queue-manager=TEST_MANAGER", "iib.transports.ibmmq.channel=TEST_CHANNEL",
                "iib.transports.kafka.enabled=true", "iib.transports.kafka.bootstrap-servers=localhost:9092").run(result -> {
            assertThat(result).hasNotFailed();
            JmsComponent artemis = result.getBean("artemis", JmsComponent.class);
            JmsComponent ibmmq = result.getBean("ibmmq", JmsComponent.class);
            assertThat(artemis.getConnectionFactory()).isSameAs(result.getBean("artemisConnectionFactory"));
            assertThat(ibmmq.getConnectionFactory()).isSameAs(result.getBean("ibmmqConnectionFactory"));
            assertThat(artemis.getConnectionFactory()).isNotSameAs(ibmmq.getConnectionFactory());
            assertThat(artemis.getTransactionManager()).isSameAs(result.getBean("artemisTransactionManager", JmsTransactionManager.class));
            assertThat(ibmmq.getTransactionManager()).isSameAs(result.getBean("ibmmqTransactionManager", JmsTransactionManager.class));
            assertThat(artemis.isTransacted()).isTrue();
            assertThat(ibmmq.isTransacted()).isTrue();
            assertThat(result).hasSingleBean(ActiveMQConnectionFactory.class).hasSingleBean(MQConnectionFactory.class);
            KafkaComponent kafka = result.getBean("kafka", KafkaComponent.class);
            assertThat(kafka.getConfiguration().isAutoCommitEnable()).isFalse();
            assertThat(kafka.getConfiguration().isAllowManualCommit()).isTrue();
            assertThat(kafka.getConfiguration().getAutoOffsetReset()).isEqualTo("earliest");
            assertThat(result.getBeansOfType(TransportSender.class)).hasSize(3);
        });
    }

    @Test void eachEnabledTransportValidatesItsOwnConfiguration() {
        for (String transport : new String[]{"artemis", "ibmmq", "kafka"}) {
            String required = switch (transport) {
                case "artemis" -> "broker-url";
                case "ibmmq" -> "host";
                default -> "bootstrap-servers";
            };
            context.withPropertyValues("iib.transports." + transport + ".enabled=true", "iib.transports." + transport + "." + required + "=")
                    .run(result -> assertThat(result).hasFailed());
        }
    }

    @Test void propertyDescriptionsRedactAllCredentials() {
        var artemis = new ArtemisTransportProperties();
        var ibmmq = new IbmMqTransportProperties();
        var kafka = new KafkaTransportProperties();
        artemis.setPassword("secret-password");
        ibmmq.setPassword("secret-password");
        kafka.setSaslJaasConfig("secret-password");
        artemis.getTls().setTrustStorePassword("secret-password");
        assertThat(artemis.toString() + ibmmq + kafka + artemis.getTls()).doesNotContain("secret-password");
    }

    @Test void bindsFriendlyEnvironmentKeysDocumentedForDeployment() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                "IIB_TRANSPORTS_ARTEMIS_BROKER_URL", "tcp://localhost:61616",
                "IIB_TRANSPORTS_IBMMQ_QUEUE_MANAGER", "SYNTHETIC_MANAGER",
                "IIB_TRANSPORTS_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")));
        var binder = Binder.get(environment);
        assertThat(binder.bind("iib.transports.artemis", Bindable.of(ArtemisTransportProperties.class)).get().getBrokerUrl())
                .isEqualTo("tcp://localhost:61616");
        assertThat(binder.bind("iib.transports.ibmmq", Bindable.of(IbmMqTransportProperties.class)).get().getQueueManager())
                .isEqualTo("SYNTHETIC_MANAGER");
        assertThat(binder.bind("iib.transports.kafka", Bindable.of(KafkaTransportProperties.class)).get().getBootstrapServers())
                .isEqualTo("localhost:9092");
    }

    @Test void kafkaProducerConfigurationRequiresBrokerAcknowledgementAndUsesExternalSecurity() {
        var properties = new KafkaTransportProperties();
        properties.setBootstrapServers("localhost:9092");
        properties.setSecurityProtocol("SASL_SSL");
        properties.setSaslMechanism("PLAIN");
        properties.setSaslJaasConfig("externally-supplied-jaas");
        properties.getTls().setTrustStore("/synthetic/trust.p12");
        assertThat(properties.producerConfiguration()).containsEntry("acks", "all")
                .containsEntry("enable.idempotence", true).containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.jaas.config", "externally-supplied-jaas").containsEntry("ssl.truststore.location", "/synthetic/trust.p12");
    }
}
