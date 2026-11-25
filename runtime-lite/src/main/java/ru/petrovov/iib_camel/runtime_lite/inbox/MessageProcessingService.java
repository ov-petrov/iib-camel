package ru.petrovov.iib_camel.runtime_lite.inbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.petrovov.iib_camel.runtime_lite.config.RoutingCatalog;
import ru.petrovov.iib_camel.runtime_lite.enrichment.EnrichmentCatalog;
import ru.petrovov.iib_camel.runtime_lite.enrichment.EnrichmentRequests;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.outbox.MessagePublisher;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichmentResult;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichingMessageProcessor;
import ru.petrovov.iib_camel.runtime_lite.processor.MessageProcessor;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsPayloadCodec;

import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageProcessingService {

    private final RoutingCatalog catalog;
    private final Map<String, MessageProcessor> processors;
    private final Map<String, EnrichingMessageProcessor<?>> enrichingProcessors;
    private final InboundMessageStore inboundMessages;
    private final MessagePublisher publisher;
    private final EnrichmentCatalog enrichmentCatalog;
    private final EnrichmentRequests enrichmentRequests;
    private final JmsPayloadCodec payloadCodec;

    public void validateRoute(String routeId) {
        String name = catalog.route(routeId).getProcessor();
        var enrichingProcessor = enrichingProcessors.get(name);
        if (enrichingProcessor == null) {
            processor(name);
            return;
        }
        if (processors.containsKey(name)) {
            throw new IllegalArgumentException("Processor implements both contracts: " + name);
        }
        enrichmentCatalog.validate(enrichingProcessor.operation());
    }

    @Transactional
    public void process(String routeId, InboundMessage message) {
        var route = catalog.route(routeId);
        String name = route.getProcessor();
        var enrichingProcessor = enrichingProcessors.get(name);
        log.info("Получено сообщение из канала: маршрут={}, id={}", routeId, message.id());
        var format = payloadCodec.formatFor(route.getInputQueue());
        if (!inboundMessages.claim(routeId, route.getInputQueue(), name, message, format)) {
            log.debug("Повторное сообщение пропущено: маршрут={}, id={}", routeId, message.id());
            return;
        }
        log.debug("Начата обработка сообщения: маршрут={}, id={}, обработчик={}",
            routeId, message.id(), name);
        var payload = payloadCodec.decode(message, format);
        OutboundMessage result;
        if (enrichingProcessor == null) {
            result = processor(name).process(payload);
        } else {
            var request = Objects.requireNonNull(enrichingProcessor.prepareLookup(payload), "enrichment request decision");
            if (request.isPresent()) {
                enrichmentRequests.enqueue(routeId, message.id(), enrichingProcessor.operation(), request.orElseThrow());
                return;
            }
            result = enrichingProcessor.map(payload, new EnrichmentResult.Skipped<>());
        }
        publisher.send(Objects.requireNonNull(result, "outbound message"));
        log.debug("Обработка сообщения завершена: маршрут={}, id={}", routeId, message.id());
    }

    private MessageProcessor processor(String name) {
        var processor = processors.get(name);
        if (processor == null) {
            throw new IllegalArgumentException("Unknown processor: " + name);
        }
        return processor;
    }
}
