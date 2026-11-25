package ru.petrovov.iib_camel.runtime_lite.enrichment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichmentFailure;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class EnrichmentDispatch {

    private final EnrichmentStore store;
    private final EnrichmentMetrics metrics;

    public Decision beforeSend(UUID requestOutboxId) {
        var state = store.lockRequest(requestOutboxId);
        if (state.status() != EnrichmentStatus.PENDING_SEND) {
            return state.failure() == EnrichmentFailure.DISPATCH_TIMEOUT ? Decision.DISPATCH_TIMEOUT : Decision.REQUEST_CLOSED;
        }
        var timeout = state.timeout(store.now());
        if (timeout != null) {
            store.readyWithFailure(state, timeout);
            return Decision.DISPATCH_TIMEOUT;
        }
        return Decision.SEND;
    }

    public void afterSend(UUID requestOutboxId, OffsetDateTime sentAt) {
        var state = store.lockRequest(requestOutboxId);
        store.sent(state, sentAt);
        metrics.dispatched(state, sentAt);
    }

    public boolean afterFailure(UUID requestOutboxId, boolean attemptsExhausted) {
        var state = store.lockRequest(requestOutboxId);
        var timeout = state.timeout(store.now());
        if (timeout != null) {
            store.readyWithFailure(state, timeout);
            return false;
        }
        if (attemptsExhausted) {
            store.readyWithFailure(state, EnrichmentFailure.DISPATCH_FAILED);
        } else {
            metrics.retry(state);
        }
        return true;
    }

    public enum Decision {
        SEND,
        DISPATCH_TIMEOUT,
        REQUEST_CLOSED
    }
}
