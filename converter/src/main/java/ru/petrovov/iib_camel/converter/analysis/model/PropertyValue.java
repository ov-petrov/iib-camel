package ru.petrovov.iib_camel.converter.analysis.model;

import java.util.List;

public record PropertyValue(String value, Origin origin, List<SourceRef> sources) {
    public PropertyValue {
        sources = List.copyOf(sources);
    }

    public static PropertyValue declared(String value, SourceRef source) {
        return new PropertyValue(value, Origin.DECLARED, List.of(source));
    }

    public static PropertyValue unknown(SourceRef source) {
        return new PropertyValue("", Origin.UNKNOWN, List.of(source));
    }

    public enum Origin {
        DECLARED,
        RESOLVED,
        UNKNOWN
    }
}
