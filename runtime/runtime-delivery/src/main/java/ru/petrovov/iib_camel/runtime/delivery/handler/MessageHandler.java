package ru.petrovov.iib_camel.runtime.delivery.handler;

import ru.petrovov.iib_camel.runtime.message.InboundMessage;
import ru.petrovov.iib_camel.runtime.message.OutboundMessage;

import java.util.List;

@FunctionalInterface
public interface MessageHandler {
    List<OutboundMessage> handle(InboundMessage message) throws Exception;
}
