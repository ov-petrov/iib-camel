package ru.petrovov.iib_camel.runtime_lite.transport;

import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxMessage;

public interface MessageSender {

    void send(OutboxMessage message) throws Exception;
}
