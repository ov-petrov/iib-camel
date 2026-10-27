package ru.petrovov.iib_camel.runtime_lite.inbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.petrovov.iib_camel.runtime_lite.config.RoutingCatalog;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.outbox.MessagePublisher;
import ru.petrovov.iib_camel.runtime_lite.processor.MessageProcessor;

import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MessageProcessingService {

    private final RoutingCatalog catalog;
    private final Map<String, MessageProcessor> processors;
    private final ReceiptStore receipts;
    private final MessagePublisher publisher;

    public void validateRoute(String routeId) {
        processor(catalog.route(routeId).getProcessor());
    }

    @Transactional
    public void process(String routeId, InboundMessage message) {
        var route = catalog.route(routeId);
        if (!receipts.claim(routeId, message.id())) {
            return;
        }
        var result = Objects.requireNonNull(processor(route.getProcessor()).process(message),
            "MessageProcessor must return the final payload");
        publisher.send(route.getOutput(), result);
    }

    private MessageProcessor processor(String name) {
        var processor = processors.get(name);
        if (processor == null) {
            throw new IllegalArgumentException("Unknown processor: " + name);
        }
        return processor;
    }
}
