package ru.petrovov.iib_camel.runtime.modules.example;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.camel.Processor;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.kafka.consumer.KafkaManualCommit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import ru.petrovov.iib_camel.runtime.delivery.InboxService;
import ru.petrovov.iib_camel.runtime.message.InboundMessage;
import ru.petrovov.iib_camel.runtime.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime.module.IntegrationModule;

@AutoConfiguration(afterName = "ru.petrovov.iib_camel.runtime.delivery.RuntimeDeliveryAutoConfiguration")
@ConditionalOnProperty(prefix = "iib.modules.example", name = "enabled", havingValue = "true")
public class ExampleModuleConfiguration {
    @Bean IntegrationModule exampleModule() {
        return new IntegrationModule("example", "classpath*:modules/example/routes/*.xml");
    }

    @Bean(name = "exampleReceive")
    Processor exampleReceive(InboxService inbox,
            @Value("${iib.modules.example.source-id:example-input}") String source,
            @Value("${iib.modules.example.output-transport:artemis}") String transport,
            @Value("${iib.modules.example.output-destination:EXAMPLE.OUT}") String destination) {
        return exchange -> {
            var message = exchange.getMessage();
            Object body = message.getBody();
            byte[] bytes;
            if (body instanceof byte[] raw) bytes = raw;
            else if (body instanceof String text) bytes = text.getBytes(StandardCharsets.UTF_8);
            else throw new IllegalArgumentException("Example accepts bytes or UTF-8 text only");
            String id = message.getHeader("applicationEventId", String.class);
            boolean kafka = message.getHeader(KafkaConstants.OFFSET) != null;
            String scopedSource = source;
            if (id == null && kafka) {
                scopedSource += ":" + message.getHeader(KafkaConstants.TOPIC) + ":" + message.getHeader(KafkaConstants.PARTITION);
                id = String.valueOf(message.getHeader(KafkaConstants.OFFSET));
            }
            if (id == null) id = message.getHeader("JMSMessageID", String.class);
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Stable input identity is required");
            KafkaManualCommit commit = message.getHeader(KafkaConstants.MANUAL_COMMIT, KafkaManualCommit.class);
            if (kafka && commit == null) throw new IllegalStateException("Kafka requires manual commit after PostgreSQL commit");
            var input = new InboundMessage("example", scopedSource, id, bytes, List.of());
            inbox.accept(input, incoming -> List.of(new OutboundMessage(UUID.randomUUID(), transport,
                    destination, incoming.payload(), incoming.headers(), incoming.messageId())));
            // This is deliberately on the consumer thread, after the database commit has returned.
            if (commit != null) commit.commit();
        };
    }
}
