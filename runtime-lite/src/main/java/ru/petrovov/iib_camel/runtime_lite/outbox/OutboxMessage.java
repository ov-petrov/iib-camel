package ru.petrovov.iib_camel.runtime_lite.outbox;

import ru.petrovov.iib_camel.runtime_lite.message.MessagePayload;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;

import java.util.UUID;

public record OutboxMessage(UUID id, Transport transport, String broker, String destination,
                            MessagePayload payload, int attempts) {
}
