package ru.petrovov.iib_camel.runtime_lite.enrichment;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichmentFailure;

import java.time.Duration;
import java.time.OffsetDateTime;

@Slf4j
@Component
class EnrichmentMetrics {

    private final MeterRegistry meters;

    EnrichmentMetrics(MeterRegistry meters, EnrichmentStore store, EnrichmentCatalog catalog) {
        this.meters = meters;
        if (!catalog.replyQueues().isEmpty()) {
            Gauge.builder("runtime.lite.enrichment.active", store, EnrichmentStore::activeCount).register(meters);
            Gauge.builder("runtime.lite.enrichment.oldest.active", store, EnrichmentStore::oldestActiveSeconds)
                .baseUnit("seconds").register(meters);
        }
    }

    void dispatched(EnrichmentStore.State state, OffsetDateTime sentAt) {
        afterCommit(() -> meters.timer("runtime.lite.enrichment.dispatch", "operation", state.operationKey())
            .record(Duration.between(state.createdAt(), sentAt)));
    }

    void replyReceived(EnrichmentStore.State state, OffsetDateTime receivedAt) {
        if (state.sentAt() != null) {
            afterCommit(() -> meters.timer("runtime.lite.enrichment.reply.wait", "operation", state.operationKey())
                .record(Duration.between(state.sentAt(), receivedAt)));
        }
    }

    void terminal(EnrichmentStore.State state, EnrichmentFailure failure) {
        String outcome = failure == null ? EnrichmentStatus.COMPLETED.name() : failure.name();
        afterCommit(() -> {
            meters.counter("runtime.lite.enrichment.outcomes", "operation", state.operationKey(), "outcome", outcome).increment();
            log.info("JMS-обогащение завершено: id={}, маршрут={}, операция={}, результат={}",
                state.id(), state.routeId(), state.operationKey(), outcome);
        });
    }

    void discarded(DiscardedReply reason) {
        afterCommit(() -> meters.counter("runtime.lite.enrichment.replies.discarded", "reason", reason.name()).increment());
    }

    void retry(EnrichmentStore.State state) {
        afterCommit(() -> meters.counter("runtime.lite.enrichment.dispatch.retries", "operation", state.operationKey()).increment());
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    enum DiscardedReply {
        MISSING_CORRELATION,
        UNKNOWN_CORRELATION,
        WRONG_QUEUE,
        DUPLICATE,
        TERMINAL,
        LATE
    }
}
