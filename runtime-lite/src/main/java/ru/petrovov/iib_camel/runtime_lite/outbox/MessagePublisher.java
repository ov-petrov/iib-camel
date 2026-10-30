package ru.petrovov.iib_camel.runtime_lite.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.petrovov.iib_camel.runtime_lite.config.RoutingCatalog;
import ru.petrovov.iib_camel.runtime_lite.message.MessagePayload;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessagePublisher {

    private final RoutingCatalog catalog;
    private final OutboxStore store;

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID send(String destination, MessagePayload payload) {
        var target = catalog.destination(destination);
        UUID id = store.append(target.getTransport(), target.getName(), payload);
        log.debug("Сообщение добавлено в транзакцию outbox: id={}, назначение={}", id, destination);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("Сообщение успешно сохранено в outbox: id={}, назначение={}", id, destination);
            }
        });
        return id;
    }
}
