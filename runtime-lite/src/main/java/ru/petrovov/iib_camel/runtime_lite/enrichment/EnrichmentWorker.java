package ru.petrovov.iib_camel.runtime_lite.enrichment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import ru.petrovov.iib_camel.runtime_lite.config.EnrichmentProperties;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.outbox.MessagePublisher;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichmentFailure;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichmentResult;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichingMessageProcessor;
import ru.petrovov.iib_camel.runtime_lite.processor.ExternalEnrichmentRejectedException;
import ru.petrovov.iib_camel.runtime_lite.processor.JmsEnrichmentOperation;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsPayloadCodec;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsPayloadFormat;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

@Slf4j
@Component
@RequiredArgsConstructor
class EnrichmentWorker implements SmartInitializingSingleton {

    private final EnrichmentCatalog catalog;
    private final EnrichmentStore store;
    private final EnrichmentMetrics metrics;
    private final Map<String, EnrichingMessageProcessor<?>> processors;
    private final MessagePublisher publisher;
    private final EnrichmentProperties properties;
    private final TransactionTemplate outboxTransaction;
    private final JmsPayloadCodec payloadCodec;

    @Override
    public void afterSingletonsInstantiated() {
        if (catalog.replyQueues().isEmpty()) {
            return;
        }
        for (var contract : store.activeContracts()) {
            var processor = processors.get(contract.processorId());
            if (processor == null || processor.operation() != catalog.operation(contract.operationKey())
                    || !catalog.replyQueues().contains(contract.replyQueue())) {
                throw new IllegalStateException("Active enrichment requires processor, operation and reply queue for: "
                    + contract.processorId() + "/" + contract.operationKey());
            }
        }
    }

    int resumeBatch() {
        return runBatch(this::resumeNext);
    }

    int expireBatch() {
        return runBatch(() -> expireNext(EnrichmentStatus.PENDING_SEND))
            + runBatch(() -> expireNext(EnrichmentStatus.WAITING_REPLY));
    }

    private int runBatch(BooleanSupplier task) {
        int processed = 0;
        while (processed < properties.getBatchSize() && !Thread.currentThread().isInterrupted()) {
            if (!Boolean.TRUE.equals(outboxTransaction.execute(status -> task.getAsBoolean()))) {
                break;
            }
            processed++;
        }
        return processed;
    }

    private boolean expireNext(EnrichmentStatus status) {
        var candidate = store.lockExpired(status);
        if (candidate.isEmpty()) {
            return false;
        }
        var state = candidate.orElseThrow();
        var timeout = state.timeout(store.now());
        if (timeout == null) {
            return false;
        }
        store.readyWithFailure(state, timeout);
        return true;
    }

    private boolean resumeNext() {
        var next = store.lockContinuation();
        if (next.isEmpty()) {
            return false;
        }
        var continuation = next.orElseThrow();
        mapContinuation(continuation, processors.get(continuation.processorId()));
        return true;
    }

    private <R> void mapContinuation(EnrichmentStore.Continuation continuation, EnrichingMessageProcessor<R> processor) {
        var state = continuation.state();
        var operation = processor == null ? null : processor.operation();
        if (operation == null || operation != catalog.operation(state.operationKey())) {
            throw new IllegalStateException("Enrichment processor or operation no longer available for workflow " + state.id());
        }
        EnrichmentResult<R> result = state.failure() == null
            ? decodeReply(operation, continuation.reply(), state.replyPayloadFormat())
            : new EnrichmentResult.Failure<>(state.failure());
        var reason = result instanceof EnrichmentResult.Failure<R> failed ? failed.reason() : null;

        UUID resultId;
        try {
            var input = payloadCodec.decode(continuation.input(), continuation.inputFormat());
            var outgoing = Objects.requireNonNull(processor.map(input, result), "outbound message");
            resultId = publisher.send(outgoing);
        } catch (DataAccessException | TransactionException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            var failureCode = reason == null ? EnrichmentFailure.MAPPING_FAILED : reason;
            store.finish(state, failureCode, null);
            metrics.terminal(state, failureCode);
            log.warn("Ошибка маппинга результата JMS-обогащения: id={}, причина={}, тип={}",
                state.id(), reason, failure.getClass().getSimpleName());
            return;
        }

        store.finish(state, reason, resultId);
        metrics.terminal(state, reason);
    }

    private <R> EnrichmentResult<R> decodeReply(JmsEnrichmentOperation<R> operation, InboundMessage reply,
                                              JmsPayloadFormat format) {
        try {
            return new EnrichmentResult.Success<>(operation.decode(payloadCodec.decode(reply, format)));
        } catch (DataAccessException | TransactionException failure) {
            throw failure;
        } catch (ExternalEnrichmentRejectedException rejection) {
            return new EnrichmentResult.Failure<>(EnrichmentFailure.EXTERNAL_REJECTED);
        } catch (RuntimeException invalid) {
            return new EnrichmentResult.Failure<>(EnrichmentFailure.INVALID_REPLY);
        }
    }
}
