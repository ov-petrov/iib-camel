package ru.petrovov.iib_camel.runtime_lite.message;

import ru.petrovov.iib_camel.runtime_lite.xml.ValidatedXml;

import java.util.Objects;

public record MessageBody(String text, Format format) {

    public MessageBody {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(format, "format");
    }

    public static MessageBody text(String text) {
        return new MessageBody(text, Format.TEXT);
    }

    public static MessageBody xml(ValidatedXml xml) {
        return new MessageBody(Objects.requireNonNull(xml, "xml").text(), Format.XML);
    }

    public enum Format {
        TEXT,
        XML
    }
}
