package ru.petrovov.iib_camel.runtime.message;

import java.util.List;
import java.util.Objects;

public record InboundMessage(String moduleId, String source, String messageId, byte[] payload,
                             List<MessageHeader> headers) {
    public InboundMessage {
        if (moduleId == null || moduleId.isBlank() || source == null || source.isBlank()
                || messageId == null || messageId.isBlank()) throw new IllegalArgumentException("Stable inbound identity is required");
        payload = Objects.requireNonNull(payload, "payload").clone();
        headers = List.copyOf(headers);
    }
    @Override public byte[] payload() { return payload.clone(); }
}
