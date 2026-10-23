package ru.petrovov.iib_camel.runtime.config;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.JMSException;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.connection.UserCredentialsConnectionFactoryAdapter;

@Configuration(proxyBeanMethods = false)
@Profile("ibm-mq")
@EnableConfigurationProperties(IbmMqProperties.class)
public class IbmMqConfig {

    @Bean(name = "connectionFactory", destroyMethod = "stop")
    public JmsPoolConnectionFactory connectionFactory(IbmMqProperties properties) throws JMSException {
        var target = mqConnectionFactory(properties);
        var credentials = new UserCredentialsConnectionFactoryAdapter();
        credentials.setTargetConnectionFactory(target);
        credentials.setUsername(properties.user());
        credentials.setPassword(properties.password());
        var pool = new JmsPoolConnectionFactory();
        pool.setConnectionFactory(credentials);
        pool.setMaxConnections(properties.maxConnections());
        pool.setMaxSessionsPerConnection(properties.maxSessionsPerConnection());
        pool.setBlockIfSessionPoolIsFull(true);
        pool.setBlockIfSessionPoolIsFullTimeout(properties.poolWaitTimeout());
        return pool;
    }

    public MQConnectionFactory mqConnectionFactory(IbmMqProperties properties) throws JMSException {
        properties.validate();
        var factory = new MQConnectionFactory();
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setHostName(properties.host());
        factory.setPort(properties.port());
        factory.setQueueManager(properties.queueManager());
        factory.setChannel(properties.channel());
        factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        if (properties.sslCipherSuite() != null && !properties.sslCipherSuite().isBlank()) {
            factory.setSSLCipherSuite(properties.sslCipherSuite());
        }
        if (properties.sslPeerName() != null && !properties.sslPeerName().isBlank()) {
            factory.setSSLPeerName(properties.sslPeerName());
        }
        return factory;
    }
}
