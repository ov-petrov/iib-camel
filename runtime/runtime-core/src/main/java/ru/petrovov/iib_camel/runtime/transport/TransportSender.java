package ru.petrovov.iib_camel.runtime.transport;

import ru.petrovov.iib_camel.runtime.message.OutboundMessage;

public interface TransportSender {
    String transport();
    void send(OutboundMessage message) throws Exception;
}
