package ru.petrovov.iib_camel.runtime.transport.config;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
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

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "iib.transports.artemis", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ArtemisTransportProperties.class)
public class ArtemisTransportConfiguration {
    @Bean(destroyMethod = "close")
    ActiveMQConnectionFactory artemisNativeConnectionFactory(ArtemisTransportProperties properties) {
        properties.validate();
        var factory = new ActiveMQConnectionFactory(properties.getBrokerUrl());
        factory.setUser(properties.getUsername());
        factory.setPassword(properties.getPassword());
        var tls = properties.getTls();
        if (tls.isEnabled()) {
            for (var connector : factory.getStaticConnectors()) {
                Map<String, Object> parameters = connector.getParams();
                parameters.put("sslEnabled", true);
                parameters.put("verifyHost", true);
                put(parameters, "trustStorePath", tls.getTrustStore());
                put(parameters, "trustStorePassword", tls.getTrustStorePassword());
                put(parameters, "trustStoreType", tls.getTrustStoreType());
                put(parameters, "keyStorePath", tls.getKeyStore());
                put(parameters, "keyStorePassword", tls.getKeyStorePassword());
                put(parameters, "keyStoreType", tls.getKeyStoreType());
            }
        }
        return factory;
    }

    private static void put(Map<String, Object> values, String name, String value) {
        if (value != null && !value.isBlank()) values.put(name, value);
    }

    @Bean(destroyMethod = "stop")
    JmsPoolConnectionFactory artemisConnectionFactory(
        @Qualifier("artemisNativeConnectionFactory") ActiveMQConnectionFactory factory,
        ArtemisTransportProperties properties) {
        return JmsTransportSupport.pool(factory, properties);
    }

    @Bean
    JmsTransactionManager artemisTransactionManager(
        @Qualifier("artemisConnectionFactory") JmsPoolConnectionFactory factory) {
        return new JmsTransactionManager(factory);
    }

    @Bean
    JmsTemplate artemisJmsTemplate(
        @Qualifier("artemisConnectionFactory") JmsPoolConnectionFactory factory) {
        return JmsTransportSupport.template(factory);
    }

    @Bean("artemis")
    JmsComponent artemisComponent(
        @Qualifier("artemisConnectionFactory") JmsPoolConnectionFactory factory,
        @Qualifier("artemisTransactionManager") JmsTransactionManager transactionManager) {
        return JmsTransportSupport.component(factory, transactionManager);
    }

    @Bean
    JmsTransportSender artemisTransportSender(
        @Qualifier("artemisJmsTemplate") JmsTemplate template,
        @Qualifier("artemisTransactionManager") JmsTransactionManager transactionManager) {
        return new JmsTransportSender("artemis", template, transactionManager);
    }
}
