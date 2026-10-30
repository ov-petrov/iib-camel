package ru.petrovov.iib_camel.runtime_lite.processor;

import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;

@FunctionalInterface
public interface MessageProcessor {

    OutboundMessage process(InboundMessage message);
}
