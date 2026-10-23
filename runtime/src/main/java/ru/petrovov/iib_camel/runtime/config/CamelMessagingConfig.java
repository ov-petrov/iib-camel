package ru.petrovov.iib_camel.runtime.config;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import org.apache.camel.Exchange;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.component.jms.JmsMessageType;
import org.apache.camel.component.jms.MessageCreatedStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.connection.JmsTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
public class CamelMessagingConfig {

    @Bean(name = "jms")
    public JmsComponent jmsComponent(ConnectionFactory connectionFactory, MessagingProperties properties) {
        var jms = JmsComponent.jmsComponentTransacted(connectionFactory, new JmsTransactionManager(connectionFactory));
        jms.setConcurrentConsumers(properties.concurrentConsumers());
        jms.setPreserveMessageQos(true);
        jms.setIncludeAllJMSXProperties(true);
        jms.setDisableReplyTo(true);
        jms.setJmsMessageType(JmsMessageType.Text);
        jms.setMessageCreatedStrategy(new PriorityTransfer());
        return jms;
    }

    public JmsComponent jmsComponent(ConnectionFactory connectionFactory) {
        return jmsComponent(connectionFactory, MessagingProperties.defaults());
    }

    static final class PriorityTransfer implements MessageCreatedStrategy {
        @Override
        public void onMessageCreated(Message created, Session session, Exchange exchange, Throwable cause) {
            Object value = exchange.getIn().getHeader("JMSPriority");
            try {
                if (value instanceof Integer priority && priority >= 0 && priority <= 9) {
                    created.setJMSPriority(priority);
                }
            } catch (JMSException e) {
                throw new IllegalStateException("Cannot transfer JMS priority", e);
            }
        }
    }
}
