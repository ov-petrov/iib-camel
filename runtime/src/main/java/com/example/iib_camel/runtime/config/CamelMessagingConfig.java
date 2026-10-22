package com.example.iib_camel.runtime.config;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.component.jms.JmsMessageType;
import org.apache.camel.component.jms.MessageCreatedStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
@Slf4j
@Configuration
public class CamelMessagingConfig {

    @Bean(name = "jms")
    public JmsComponent jmsComponent(ConnectionFactory connectionFactory) {
        JmsComponent jms = JmsComponent.jmsComponentAutoAcknowledge(connectionFactory);
        jms.setPreserveMessageQos(true);
        // тело POJO (ULM) на выходе сериализуется обратно в XML-текст (IIB Copy Body)
        jms.setJmsMessageType(JmsMessageType.Text);
        // перенос QoS (MQMD.Priority → JMSPriority): приоритет берётся из
        // входного сообщения ('JMSPriority' header выставляется consumer'ом)
        jms.setMessageCreatedStrategy(new PriorityTransfer());
        return jms;
    }

    /**
     * Аналог копирования MQMD из InputRoot в OutputRoot при отправке нового JMS-сообщения:
     * приоритет и delivery mode переносятся с входного сообщения.
     */
    static final class PriorityTransfer implements MessageCreatedStrategy {

        @Override
        public void onMessageCreated(Message created, Session session,
                                     Exchange exchange, Throwable cause) {
            Object in = exchange.getIn().getHeader("JMSPriority");
            try {
                if (in instanceof Integer priority && priority >= 0 && priority <= 9) {
                    created.setJMSPriority(priority);
                    log.debug("перенос QoS: JMSPriority={} → исходящее сообщение", priority);
                }
            } catch (JMSException e) {
                throw new IllegalStateException("не удалось перенести приоритет (MQMD Priority)", e);
            }
        }
    }
}
