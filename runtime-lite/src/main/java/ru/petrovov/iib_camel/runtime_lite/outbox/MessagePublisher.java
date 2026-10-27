package ru.petrovov.iib_camel.runtime_lite.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.petrovov.iib_camel.runtime_lite.config.RoutingCatalog;
import ru.petrovov.iib_camel.runtime_lite.message.MessagePayload;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MessagePublisher {

    private final RoutingCatalog catalog;
    private final OutboxStore store;

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID send(String destination, MessagePayload payload) {
        var target = catalog.destination(destination);
        return store.append(target.getTransport(), target.getName(), payload);
    }
}
