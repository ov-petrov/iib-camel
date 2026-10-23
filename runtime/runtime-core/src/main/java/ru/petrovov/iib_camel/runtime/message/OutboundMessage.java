package ru.petrovov.iib_camel.runtime.message;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OutboundMessage(
    UUID id,
    String transport,
    String destination,
    byte[] payload,
    List<MessageHeader> headers,
    String key) {
    public OutboundMessage {
        Objects.requireNonNull(id, "id");
        if (transport == null || transport.isBlank() || destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("Transport and destination are required");
        }
        payload = Objects.requireNonNull(payload, "payload").clone();
        headers = List.copyOf(headers);
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
