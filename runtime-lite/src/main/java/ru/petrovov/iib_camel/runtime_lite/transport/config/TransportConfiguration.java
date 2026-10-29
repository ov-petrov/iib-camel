package ru.petrovov.iib_camel.runtime_lite.transport.config;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
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
import ru.petrovov.iib_camel.runtime_lite.transport.BrokerMessageSender;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsMessageReader;
import ru.petrovov.iib_camel.runtime_lite.transport.MessageSender;

import java.util.HashMap;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({JmsProperties.class, MqProperties.class, ArtemisProperties.class,
    KafkaProducerProperties.class, ListenerProperties.class})
public class TransportConfiguration {

    @Bean
    @ConditionalOnMissingBean(ConnectionFactory.class)
    @ConditionalOnProperty(prefix = "runtime-lite.jms", name = "provider", havingValue = "IBM_MQ", matchIfMissing = true)
    ConnectionFactory liteConnectionFactory(MqProperties properties) throws Exception {
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
    @ConditionalOnMissingBean(ConnectionFactory.class)
    @ConditionalOnProperty(prefix = "runtime-lite.jms", name = "provider", havingValue = "ARTEMIS")
    ConnectionFactory artemisConnectionFactory(ArtemisProperties properties) {
        var factory = new ActiveMQConnectionFactory(properties.getBrokerUrl());
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            factory.setUser(properties.getUsername());
            factory.setPassword(properties.getPassword());
        }
        return factory;
    }

    @Bean
    DefaultJmsListenerContainerFactory liteJmsListenerContainerFactory(
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
    MessageSender messageSender(ConnectionFactory connectionFactory, KafkaTemplate<String, byte[]> kafkaTemplate,
        KafkaProducerProperties properties) {
        return new BrokerMessageSender(connectionFactory, kafkaTemplate, properties);
    }
}
