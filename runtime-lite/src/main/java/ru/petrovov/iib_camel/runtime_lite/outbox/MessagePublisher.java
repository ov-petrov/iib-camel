package ru.petrovov.iib_camel.runtime_lite.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessagePublisher {

    private final OutboxStore store;

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID send(OutboundMessage message) {
        UUID id = store.append(message);
        log.debug("Сообщение добавлено в транзакцию outbox: id={}, транспорт={}, канал={}",
            id, message.transport(), message.destination());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("Сообщение успешно сохранено в outbox: id={}, транспорт={}, канал={}",
                    id, message.transport(), message.destination());
            }
        });
        return id;
    }
}
