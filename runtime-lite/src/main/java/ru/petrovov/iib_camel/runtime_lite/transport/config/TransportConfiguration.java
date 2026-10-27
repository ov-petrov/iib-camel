package ru.petrovov.iib_camel.runtime_lite.transport.config;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.ConnectionFactory;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import ru.petrovov.iib_camel.runtime_lite.transport.*;

import java.util.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MqProperties.class, KafkaProducerProperties.class, ListenerProperties.class,
    TransportsProperties.class})
public class TransportConfiguration {

    @Bean
    @ConditionalOnMissingBean(ConnectionFactory.class)
    @ConditionalOnProperty(prefix = "runtime-lite.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
    ConnectionFactory liteConnectionFactory(MqProperties properties) throws Exception {
        return createMqFactory(properties);
    }

    private MQConnectionFactory createMqFactory(MqProperties properties) throws Exception {
        var factory = new MQConnectionFactory();
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setHostName(properties.getHost());
        factory.setPort(properties.getPort());
        factory.setQueueManager(properties.getQueueManager());
        factory.setChannel(properties.getChannel());
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            factory.setStringProperty(WMQConstants.USERID, properties.getUsername());
            factory.setStringProperty(WMQConstants.PASSWORD, properties.getPassword());
            factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        }
        if (properties.getCipherSuite() != null && !properties.getCipherSuite().isBlank()) {
            factory.setSSLCipherSuite(properties.getCipherSuite());
            if (properties.getPeerName() != null) {
                factory.setSSLPeerName(properties.getPeerName());
            }
        }
        return factory;
    }

    @Bean
    DefaultJmsListenerContainerFactory liteJmsListenerContainerFactory(
        ConnectionFactory connectionFactory, ListenerProperties properties) {
        return createListenerFactory(connectionFactory, properties);
    }

    private DefaultJmsListenerContainerFactory createListenerFactory(
        ConnectionFactory connectionFactory, ListenerProperties properties) {
        var factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setSessionTransacted(true);
        factory.setAutoStartup(properties.isAutoStartup());
        return factory;
    }

    @Bean
    JmsMessageReader jmsMessageReader(ListenerProperties properties) {
        return new JmsMessageReader(properties);
    }

    @Bean
    DefaultKafkaProducerFactory<String, byte[]> liteKafkaProducerFactory(KafkaProducerProperties properties) {
        long timeout = properties.getSendTimeout().toMillis();
        if (timeout < 1 || timeout > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Kafka send timeout must be between 1 ms and 2147483647 ms");
        }
        var configuration = new HashMap<String, Object>();
        configuration.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers());
        configuration.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, timeout);
        configuration.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) timeout);
        configuration.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) timeout);
        configuration.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        configuration.putAll(properties.getProperties());
        configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        configuration.put(ProducerConfig.ACKS_CONFIG, "all");
        configuration.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(configuration);
    }

    @Bean
    KafkaTemplate<String, byte[]> liteKafkaTemplate(DefaultKafkaProducerFactory<String, byte[]> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    BrokerRegistry brokerRegistry(ConnectionFactory defaultConnectionFactory,
                                  DefaultJmsListenerContainerFactory liteJmsListenerContainerFactory,
                                  KafkaTemplate<String, byte[]> liteKafkaTemplate, KafkaProducerProperties kafkaProperties,
                                  TransportsProperties transports, ListenerProperties listenerProperties) throws Exception {
        validateNames(transports);
        Map<String, ConnectionFactory> jms = new LinkedHashMap<>();
        Map<String, DefaultJmsListenerContainerFactory> listeners = new LinkedHashMap<>();
        Map<String, BrokerRegistry.KafkaBroker> kafka = new LinkedHashMap<>();
        var ownedFactories = new ArrayList<DefaultKafkaProducerFactory<String, byte[]>>();
        jms.put(BrokerIds.DEFAULT_MQ, defaultConnectionFactory);
        listeners.put(BrokerIds.DEFAULT_MQ, liteJmsListenerContainerFactory);
        kafka.put(BrokerIds.DEFAULT_KAFKA,
            new BrokerRegistry.KafkaBroker(liteKafkaTemplate, kafkaProperties.getSendTimeout()));
        try {
            for (var entry : transports.getMq().entrySet()) {
                if (!entry.getValue().isEnabled()) {
                    throw new IllegalArgumentException("Named MQ broker must be enabled: " + entry.getKey());
                }
                var factory = createMqFactory(entry.getValue());
                jms.put(entry.getKey(), factory);
                listeners.put(entry.getKey(), createListenerFactory(factory, listenerProperties));
            }
            for (var entry : transports.getKafka().entrySet()) {
                var factory = liteKafkaProducerFactory(entry.getValue());
                ownedFactories.add(factory);
                kafka.put(entry.getKey(), new BrokerRegistry.KafkaBroker(
                    new KafkaTemplate<>(factory), entry.getValue().getSendTimeout()));
            }
            return new BrokerRegistry(jms, listeners, kafka, ownedFactories);
        } catch (Exception | Error failure) {
            for (var factory : ownedFactories) {
                try {
                    factory.destroy();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    private void validateNames(TransportsProperties properties) {
        var reserved = Set.of(BrokerIds.DEFAULT_MQ, BrokerIds.DEFAULT_KAFKA);
        for (var id : properties.getMq().keySet()) {
            if (reserved.contains(id) || properties.getKafka().containsKey(id)) {
                throw new IllegalArgumentException("Duplicate or reserved broker name: " + id);
            }
        }
        for (var id : properties.getKafka().keySet()) {
            if (reserved.contains(id)) {
                throw new IllegalArgumentException("Duplicate or reserved broker name: " + id);
            }
        }
    }

    @Bean
    MessageSender messageSender(BrokerRegistry brokerRegistry) {
        return new BrokerMessageSender(brokerRegistry);
    }
}
