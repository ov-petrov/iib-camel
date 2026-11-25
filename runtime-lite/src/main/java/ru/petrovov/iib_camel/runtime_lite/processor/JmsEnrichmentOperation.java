package ru.petrovov.iib_camel.runtime_lite.processor;

import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.MessageBody;

public interface JmsEnrichmentOperation<R> {

    String key();

    default MessageBody validateReply(InboundMessage reply) {
        return MessageBody.text(reply.body());
    }

    R decode(InboundMessage reply);
}
