package ru.petrovov.iib_camel.runtime_lite.outbox;

import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;

import java.util.UUID;

public record OutboxMessage(UUID id, OutboundMessage message, int attempts) {
}
