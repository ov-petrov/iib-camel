package ru.petrovov.iib_camel.runtime_lite.transport.config;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import ru.petrovov.iib_camel.runtime_lite.transport.BrokerMessageSender;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsMessageReader;
import ru.petrovov.iib_camel.runtime_lite.transport.MessageSender;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({JmsProperties.class, MqProperties.class, ArtemisProperties.class,
    ListenerProperties.class})
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
    MessageSender messageSender(ConnectionFactory connectionFactory, KafkaTemplate<String, String> kafkaTemplate) {
        return new BrokerMessageSender(connectionFactory, kafkaTemplate);
    }
}
