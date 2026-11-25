package ru.petrovov.iib_camel.runtime_lite.enrichment;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.petrovov.iib_camel.runtime_lite.enrichment.EnrichmentMetrics.DiscardedReply;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.MessageBody;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichmentFailure;
import ru.petrovov.iib_camel.runtime_lite.processor.ExternalEnrichmentRejectedException;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsMessageReader;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsPayloadCodec;
import ru.petrovov.iib_camel.runtime_lite.xml.XmlProcessingException;
import ru.petrovov.iib_camel.runtime_lite.xml.schema.XmlSchemaException;

@Service
@RequiredArgsConstructor
public class EnrichmentReplyReceiver {

    private final EnrichmentStore store;
    private final EnrichmentMetrics metrics;
    private final JmsMessageReader reader;
    private final EnrichmentCatalog catalog;
    private final JmsPayloadCodec payloadCodec;

    @Transactional(rollbackFor = JMSException.class)
    public void receive(String queue, Message message) throws JMSException {
        String correlationId = message.getJMSCorrelationID();
        if (correlationId == null || correlationId.isBlank()) {
            metrics.discarded(DiscardedReply.MISSING_CORRELATION);
            return;
        }
        var match = store.lockReply(correlationId);
        if (match.isEmpty()) {
            metrics.discarded(DiscardedReply.UNKNOWN_CORRELATION);
            return;
        }
        var state = match.orElseThrow();
        if (!state.replyQueue().equals(queue)) {
            metrics.discarded(DiscardedReply.WRONG_QUEUE);
            return;
        }
        switch (state.status()) {
            case READY_TO_MAP, COMPLETED, FAILED -> metrics.discarded(switch (state.failure()) {
                case null -> DiscardedReply.DUPLICATE;
                case DISPATCH_TIMEOUT, REPLY_TIMEOUT -> DiscardedReply.LATE;
                default -> DiscardedReply.TERMINAL;
            });
            case PENDING_SEND, WAITING_REPLY -> {
                var receivedAt = store.now();
                var timeout = state.timeout(receivedAt);
                if (timeout != null) {
                    store.readyWithFailure(state, timeout);
                    metrics.discarded(DiscardedReply.LATE);
                    return;
                }
                InboundMessage reply;
                InboundMessage payload;
                MessageBody body;
                try {
                    reply = reader.read(message);
                    if (reply.body().isBlank()) {
                        throw new IllegalArgumentException("Enrichment reply body is blank");
                    }
                    payload = payloadCodec.decode(reply, state.replyPayloadFormat());
                    body = catalog.operation(state.operationKey()).validateReply(payload);
                } catch (ExternalEnrichmentRejectedException rejection) {
                    store.readyWithFailure(state, EnrichmentFailure.EXTERNAL_REJECTED);
                    return;
                } catch (JmsMessageReader.InvalidMessageException | IllegalArgumentException | XmlProcessingException
                         | XmlSchemaException invalid) {
                    store.readyWithFailure(state, EnrichmentFailure.INVALID_REPLY);
                    return;
                }
                if (!body.text().equals(payload.body())) {
                    throw new IllegalStateException("Validated payload must match the reply document");
                }
                store.received(state, reply, payloadCodec.wireBody(reply, body, state.replyPayloadFormat()), receivedAt);
                metrics.replyReceived(state, receivedAt);
            }
        }
    }
}
