package ru.petrovov.iib_camel.runtime.transport.config;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import org.apache.camel.component.jms.JmsComponent;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.connection.JmsTransactionManager;
import org.springframework.jms.core.JmsTemplate;
import ru.petrovov.iib_camel.runtime.transport.JmsTransportSender;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "iib.transports.ibmmq", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(IbmMqTransportProperties.class)
public class IbmMqTransportConfiguration {
    @Bean
    MQConnectionFactory ibmmqNativeConnectionFactory(IbmMqTransportProperties properties) throws Exception {
        properties.validate();
        var factory = new MQConnectionFactory();
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setHostName(properties.getHost());
        factory.setPort(properties.getPort());
        factory.setQueueManager(properties.getQueueManager());
        factory.setChannel(properties.getChannel());
        if (properties.getUsername() != null) {
            factory.setStringProperty(WMQConstants.USERID, properties.getUsername());
            factory.setStringProperty(WMQConstants.PASSWORD, properties.getPassword());
            factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        }
        if (properties.getTls().isEnabled()) {
            factory.setSSLCipherSuite(properties.getCipherSuite());
            if (properties.getSslPeerName() != null) factory.setSSLPeerName(properties.getSslPeerName());
            factory.setSSLSocketFactory(TlsContextFactory.socketFactory(properties.getTls()));
        }
        return factory;
    }

    @Bean(destroyMethod = "stop")
    JmsPoolConnectionFactory ibmmqConnectionFactory(@Qualifier("ibmmqNativeConnectionFactory") MQConnectionFactory factory,
                                                  IbmMqTransportProperties properties) {
        return JmsTransportSupport.pool(factory, properties);
    }

    @Bean
    JmsTransactionManager ibmmqTransactionManager(@Qualifier("ibmmqConnectionFactory") JmsPoolConnectionFactory factory) {
        return new JmsTransactionManager(factory);
    }

    @Bean
    JmsTemplate ibmmqJmsTemplate(@Qualifier("ibmmqConnectionFactory") JmsPoolConnectionFactory factory) {
        return JmsTransportSupport.template(factory);
    }

    @Bean("ibmmq")
    JmsComponent ibmmqComponent(@Qualifier("ibmmqConnectionFactory") JmsPoolConnectionFactory factory,
                               @Qualifier("ibmmqTransactionManager") JmsTransactionManager transactionManager) {
        return JmsTransportSupport.component(factory, transactionManager);
    }

    @Bean
    JmsTransportSender ibmmqTransportSender(@Qualifier("ibmmqJmsTemplate") JmsTemplate template,
                                           @Qualifier("ibmmqTransactionManager") JmsTransactionManager transactionManager) {
        return new JmsTransportSender("ibmmq", template, transactionManager);
    }
}
