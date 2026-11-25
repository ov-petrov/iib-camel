package ru.petrovov.iib_camel.runtime_lite.xml;

import java.util.Objects;

public final class ValidatedXml {

    private final String text;

    ValidatedXml(String text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    public String text() {
        return text;
    }
}
