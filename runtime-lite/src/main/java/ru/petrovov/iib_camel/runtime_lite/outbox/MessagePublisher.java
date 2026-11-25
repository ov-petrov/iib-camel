package ru.petrovov.iib_camel.runtime_lite.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.soap.SoapProcessingException;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsPayloadCodec;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessagePublisher {

    private final OutboxStore store;
    private final JmsPayloadCodec payloadCodec;

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = SoapProcessingException.class)
    public UUID send(OutboundMessage message) {
        return publish(message, null);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = SoapProcessingException.class)
    public UUID sendRequest(OutboundMessage message, String replyQueue) {
        if (message.transport() != Transport.JMS || replyQueue == null || replyQueue.isBlank()) {
            throw new IllegalArgumentException("A JMS request requires a reply queue");
        }
        return publish(message, replyQueue);
    }

    private UUID publish(OutboundMessage message, String replyQueue) {
        UUID id = store.append(payloadCodec.encode(message), replyQueue);
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
