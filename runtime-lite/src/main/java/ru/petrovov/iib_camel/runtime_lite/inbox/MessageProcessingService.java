package ru.petrovov.iib_camel.runtime_lite.inbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.petrovov.iib_camel.runtime_lite.config.RoutingCatalog;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.outbox.MessagePublisher;
import ru.petrovov.iib_camel.runtime_lite.processor.MessageProcessor;

import java.util.Map;
import java.util.Objects;

@Slf4j
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
        log.info("Получено сообщение из канала: маршрут={}, id={}, тело={}", routeId, message.id(), message.body());
        var route = catalog.route(routeId);
        if (!receipts.claim(routeId, message.id())) {
            log.debug("Повторное сообщение пропущено: маршрут={}, id={}", routeId, message.id());
            return;
        }
        log.debug("Начата обработка сообщения: маршрут={}, id={}, обработчик={}",
            routeId, message.id(), route.getProcessor());
        var result = Objects.requireNonNull(processor(route.getProcessor()).process(message),
            "MessageProcessor must return the outbound message");
        publisher.send(result);
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
