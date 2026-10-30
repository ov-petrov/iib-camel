package ru.petrovov.iib_camel.runtime_lite.message;

import java.util.Map;
import java.util.Objects;

public record InboundMessage(String id, String body, Map<String, String> headers) {

    public InboundMessage {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Inbound message ID is required");
        }
        body = Objects.requireNonNull(body, "body");
        headers = Map.copyOf(headers);
    }
}
