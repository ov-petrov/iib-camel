package ru.petrovov.iib_camel.runtime.delivery;

import java.util.List;
import ru.petrovov.iib_camel.runtime.message.InboundMessage;
import ru.petrovov.iib_camel.runtime.message.OutboundMessage;

@FunctionalInterface
public interface MessageHandler {
    List<OutboundMessage> handle(InboundMessage message) throws Exception;
}
