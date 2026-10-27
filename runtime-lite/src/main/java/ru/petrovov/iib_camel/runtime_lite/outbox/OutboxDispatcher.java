package ru.petrovov.iib_camel.runtime_lite.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.petrovov.iib_camel.runtime_lite.config.OutboxProperties;
import ru.petrovov.iib_camel.runtime_lite.transport.MessageSender;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcher {

    private final OutboxStore store;
    private final MessageSender sender;
    private final TransactionTemplate outboxTransaction;
    private final OutboxProperties properties;

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
        try {
            sender.send(message);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Outbox dispatch interrupted", failure);
            }
            store.markFailed(message, properties.getMaxAttempts(), properties.getRetryDelay(), failure);
            log.warn("Outbox delivery failed: id={}, attempt={}, type={}", message.id(), message.attempts() + 1,
                failure.getClass().getSimpleName());
            return true;
        }
        store.markSent(message);
        return true;
    }
}
