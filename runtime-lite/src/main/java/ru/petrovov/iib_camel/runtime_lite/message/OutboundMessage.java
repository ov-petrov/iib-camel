package ru.petrovov.iib_camel.runtime_lite.message;

import ru.petrovov.iib_camel.runtime_lite.transport.Transport;

import java.util.Map;
import java.util.Objects;

public record OutboundMessage(Transport transport, String destination, String body, String key,
                              Map<String, String> headers) {

    public OutboundMessage {
        Objects.requireNonNull(transport, "transport");
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("Outbound destination is required");
        }
        Objects.requireNonNull(body, "body");
        headers = Map.copyOf(headers);
    }

    public static OutboundMessage of(Transport transport, String destination, String body) {
        return new OutboundMessage(transport, destination, body, null, Map.of());
    }
}
