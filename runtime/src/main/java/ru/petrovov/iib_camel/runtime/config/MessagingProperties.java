package ru.petrovov.iib_camel.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("iib.messaging")
public record MessagingProperties(
        @DefaultValue("2") int maximumRedeliveries,
        @DefaultValue("1000") long redeliveryDelay,
        @DefaultValue("IIB.DLQ") String deadLetterQueue,
        @DefaultValue("1") int concurrentConsumers) {

    public MessagingProperties {
        if (maximumRedeliveries < 0 || redeliveryDelay < 0 || concurrentConsumers < 1) {
            throw new IllegalArgumentException("JMS redeliveries/delay must be nonnegative and consumers positive");
        }
        if (deadLetterQueue == null || !deadLetterQueue.matches("[A-Za-z0-9_./%-]+")) {
            throw new IllegalArgumentException("iib.messaging.dead-letter-queue must be a JMS queue name, without URI options");
        }
    }

    public static MessagingProperties defaults() {
        return new MessagingProperties(2, 1000, "IIB.DLQ", 1);
    }
}
