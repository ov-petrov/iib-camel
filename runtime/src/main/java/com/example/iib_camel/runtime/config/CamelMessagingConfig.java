package com.example.iib_camel.runtime.config;

import org.apache.camel.component.jms.JmsComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.jms.ConnectionFactory;

/**
 * Конфигурация JMS-компонента Camel (FR-R2.1, FR-R3.1).
 *
 * ConnectionFactory создаётся автоконфигурацией Spring Boot
 * (embedded Artemis в тестах; внешний брокер — через spring.jms.* / spring.artemis.*).
 *
 * preserveMessageQos=true — приоритет (JMSPriority, аналог MQMD Priority) и TTL
 * исходящего сообщения наследуются от входящего, как это делает Compute в IIB
 * (SET OutputRoot = InputRoot).
 */
@Configuration
public class CamelMessagingConfig {

    @Bean(name = "jms")
    public JmsComponent jmsComponent(ConnectionFactory connectionFactory) {
        JmsComponent jms = JmsComponent.jmsComponentAutoAcknowledge(connectionFactory);
        jms.setPreserveMessageQos(true);
        // тело POJO (ULM) на выходе сериализуется обратно в XML-текст (IIB Copy Body)
        jms.setJmsMessageType(org.apache.camel.component.jms.JmsMessageType.Text);
        // перенос QoS (MQMD.Priority → JMSPriority): приоритет берётся из
        // входного сообщения ('JMSPriority' header выставляется consumer'ом)
        jms.setMessageCreatedStrategy(new PriorityTransfer());
        return jms;
    }

    /**
     * Аналог копирования MQMD из InputRoot в OutputRoot при отправке нового JMS-сообщения:
     * приоритет и delivery mode переносятся с входного сообщения.
     */
    static final class PriorityTransfer implements org.apache.camel.component.jms.MessageCreatedStrategy {

        @Override
        public void onMessageCreated(jakarta.jms.Message created, jakarta.jms.Session session,
                                     org.apache.camel.Exchange exchange, Throwable cause) {
            Object in = exchange.getIn().getHeader("JMSPriority");
            try {
                if (in instanceof Integer priority && priority >= 0 && priority <= 9) {
                    created.setJMSPriority(priority);
                }
            } catch (jakarta.jms.JMSException e) {
                throw new IllegalStateException("не удалось перенести приоритет (MQMD Priority)", e);
            }
        }
    }
}
