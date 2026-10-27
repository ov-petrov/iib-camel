package ru.petrovov.iib_camel.runtime_lite.processor;

import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.MessagePayload;

@FunctionalInterface
public interface MessageProcessor {

    MessagePayload process(InboundMessage message);
}
