package ru.petrovov.iib_camel.runtime_lite.processor;

import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.MessageBody;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;

import java.util.Optional;

public interface EnrichingMessageProcessor<R> {

    JmsEnrichmentOperation<R> operation();

    Optional<MessageBody> prepareLookup(InboundMessage input);

    OutboundMessage map(InboundMessage input, EnrichmentResult<R> result);
}
