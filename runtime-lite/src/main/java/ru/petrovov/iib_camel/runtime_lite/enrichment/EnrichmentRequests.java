package ru.petrovov.iib_camel.runtime_lite.enrichment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.petrovov.iib_camel.runtime_lite.config.EnrichmentProperties;
import ru.petrovov.iib_camel.runtime_lite.message.MessageBody;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.outbox.MessagePublisher;
import ru.petrovov.iib_camel.runtime_lite.processor.JmsEnrichmentOperation;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsMessageReader;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsPayloadCodec;
import ru.petrovov.iib_camel.runtime_lite.transport.MessageHeaders;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EnrichmentRequests {

    private final EnrichmentProperties properties;
    private final EnrichmentCatalog catalog;
    private final EnrichmentStore store;
    private final MessagePublisher publisher;
    private final JmsMessageReader reader;
    private final JmsPayloadCodec payloadCodec;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String routeId, String messageId, JmsEnrichmentOperation<?> operation, MessageBody body) {
        var configuration = catalog.configuration(operation);
        String correlationId = UUID.randomUUID().toString();
        if (!reader.isBodyWithinLimit(body.text())) {
            throw new IllegalArgumentException("Enrichment request body exceeds the configured size limit");
        }
        var outgoing = OutboundMessage.builder()
            .transport(Transport.JMS)
            .destination(configuration.getRequestQueue())
            .payload(body)
            .headers(Map.of(MessageHeaders.CORRELATION_ID, correlationId))
            .build();
        var requestId = publisher.sendRequest(outgoing, configuration.getReplyQueue());
        var replyTimeout = configuration.getReplyTimeout() == null ? properties.getDefaultReplyTimeout()
            : configuration.getReplyTimeout();
        store.start(routeId, messageId, operation.key(), correlationId, configuration.getReplyQueue(),
            payloadCodec.formatFor(configuration.getReplyQueue()), requestId, properties.getDispatchTimeout(), replyTimeout);
    }
}
