package ru.petrovov.iib_camel.runtime.message;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record MessageHeader(String name, String type, byte[] value) {
    public MessageHeader {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Header name is required");
        Objects.requireNonNull(type, "type");
        value = Objects.requireNonNull(value, "value").clone();
    }
    @Override public byte[] value() { return value.clone(); }
    public static MessageHeader text(String name, String value) {
        return new MessageHeader(name, "STRING", value.getBytes(StandardCharsets.UTF_8));
    }
}
