package ru.petrovov.iib_camel.runtime_lite.message;

import lombok.Builder;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;

import java.util.Map;
import java.util.Objects;

@Builder
public record OutboundMessage(Transport transport, String destination, MessageBody payload, String key,
                              Map<String, String> headers) {

    public OutboundMessage {
        Objects.requireNonNull(transport, "transport");
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("Outbound destination is required");
        }
        Objects.requireNonNull(payload, "payload");
        headers = Map.copyOf(headers);
    }

    public OutboundMessage(Transport transport, String destination, String body, String key,
                           Map<String, String> headers) {
        this(transport, destination, MessageBody.text(body), key, headers);
    }

    public String body() {
        return payload.text();
    }

    public static OutboundMessage of(Transport transport, String destination, MessageBody payload, String key,
                                     Map<String, String> headers) {
        return builder()
            .transport(transport)
            .destination(destination)
            .payload(payload)
            .key(key)
            .headers(headers)
            .build();
    }

    public static OutboundMessage of(Transport transport, String destination, String body) {
        return of(transport, destination, MessageBody.text(body), null, Map.of());
    }
}
