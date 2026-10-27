package ru.petrovov.iib_camel.runtime_lite.message;

import java.util.Map;
import java.util.Objects;

public record MessagePayload(byte[] body, String key, Map<String, String> headers) {

    public MessagePayload {
        body = Objects.requireNonNull(body, "body").clone();
        headers = Map.copyOf(headers);
    }

    public static MessagePayload of(byte[] body) {
        return new MessagePayload(body, null, Map.of());
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
