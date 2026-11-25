package ru.petrovov.iib_camel.runtime_lite.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.petrovov.iib_camel.runtime_lite.config.OutboxProperties;
import ru.petrovov.iib_camel.runtime_lite.enrichment.EnrichmentDispatch;
import ru.petrovov.iib_camel.runtime_lite.transport.MessageSender;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcher {

    private final OutboxStore store;
    private final MessageSender sender;
    private final TransactionTemplate outboxTransaction;
    private final OutboxProperties properties;
    private final EnrichmentDispatch enrichmentDispatch;

    public int dispatchBatch() {
        int processed = 0;
        while (processed < properties.getBatchSize() && !Thread.currentThread().isInterrupted()) {
            boolean found = Boolean.TRUE.equals(outboxTransaction.execute(status -> dispatchNext()));
            if (!found) {
                break;
            }
            processed++;
        }
        return processed;
    }

    private boolean dispatchNext() {
        var next = store.lockNext();
        if (next.isEmpty()) {
            return false;
        }
        var message = next.orElseThrow();
        log.debug("Отправка сообщения из outbox: id={}, транспорт={}, попытка={}",
            message.id(), message.message().transport(), message.attempts() + 1);
        boolean request = message.replyQueue() != null;
        if (request) {
            var decision = enrichmentDispatch.beforeSend(message.id());
            if (decision != EnrichmentDispatch.Decision.SEND) {
                store.markCancelled(message, decision.name(), false);
                return true;
            }
        }
        try {
            sender.send(message);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Outbox dispatch interrupted", failure);
            }
            boolean attemptsExhausted = message.attempts() + 1 >= properties.getMaxAttempts();
            if (request && !enrichmentDispatch.afterFailure(message.id(), attemptsExhausted)) {
                store.markCancelled(message, EnrichmentDispatch.Decision.DISPATCH_TIMEOUT.name(), true);
                return true;
            }
            store.markFailed(message, properties.getMaxAttempts(), properties.getRetryDelay(), failure);
            log.warn("Ошибка доставки сообщения из outbox: id={}, попытка={}, тип={}", message.id(), message.attempts() + 1,
                failure.getClass().getSimpleName());
            if (!request) {
                log.debug("Подробности ошибки доставки сообщения из outbox: id={}", message.id(), failure);
            }
            return true;
        }
        var sentAt = store.markSent(message);
        if (request) {
            enrichmentDispatch.afterSend(message.id(), sentAt);
        }
        log.debug("Брокер подтвердил отправку сообщения из outbox: id={}", message.id());
        return true;
    }
}
