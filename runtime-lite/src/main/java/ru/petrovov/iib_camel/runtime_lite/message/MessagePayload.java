package ru.petrovov.iib_camel.runtime_lite.message;

import java.util.Map;
import java.util.Objects;

public record MessagePayload(String body, String key, Map<String, String> headers) {

    public MessagePayload {
        body = Objects.requireNonNull(body, "body");
        headers = Map.copyOf(headers);
    }

    public static MessagePayload of(String body) {
        return new MessagePayload(body, null, Map.of());
    }
}
