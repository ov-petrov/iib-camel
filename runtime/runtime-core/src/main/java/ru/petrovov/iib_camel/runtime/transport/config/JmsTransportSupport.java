package ru.petrovov.iib_camel.runtime.transport.config;

import jakarta.jms.ConnectionFactory;
import org.apache.camel.component.jms.JmsComponent;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.springframework.jms.connection.JmsTransactionManager;
import org.springframework.jms.core.JmsTemplate;

final class JmsTransportSupport {
    private JmsTransportSupport() {
    }

    static JmsPoolConnectionFactory pool(ConnectionFactory factory, JmsTransportProperties properties) {
        var pool = new JmsPoolConnectionFactory();
        pool.setConnectionFactory(factory);
        pool.setMaxConnections(properties.getMaxConnections());
        pool.setMaxSessionsPerConnection(properties.getMaxSessionsPerConnection());
        pool.setBlockIfSessionPoolIsFullTimeout(30000);
        return pool;
    }

    static JmsTemplate template(ConnectionFactory factory) {
        var template = new JmsTemplate(factory);
        template.setDestinationResolver((session, name, pubSub) -> {
            if (name.startsWith("topic:")) {
                return session.createTopic(name.substring(6));
            }
            return session.createQueue(name.startsWith("queue:") ? name.substring(6) : name);
        });
        template.setSessionTransacted(true);
        template.setExplicitQosEnabled(true);
        template.setDeliveryPersistent(true);
        return template;
    }

    static JmsComponent component(ConnectionFactory factory, JmsTransactionManager transactionManager) {
        var component = new JmsComponent();
        component.setConnectionFactory(factory);
        component.setTransactionManager(transactionManager);
        component.setTransacted(true);
        component.setDisableReplyTo(true);
        return component;
    }
}
