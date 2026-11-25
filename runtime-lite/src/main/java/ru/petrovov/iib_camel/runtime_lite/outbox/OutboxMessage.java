package ru.petrovov.iib_camel.runtime_lite.outbox;

import lombok.Builder;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;

import java.util.UUID;

@Builder
public record OutboxMessage(UUID id, OutboundMessage message, int attempts, String replyQueue) {

    public OutboxMessage(UUID id, OutboundMessage message, int attempts) {
        this(id, message, attempts, null);
    }
}
