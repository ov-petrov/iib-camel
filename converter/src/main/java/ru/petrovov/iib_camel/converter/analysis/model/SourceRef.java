package ru.petrovov.iib_camel.converter.analysis.model;

public record SourceRef(String file, int line, int column) {
    public static SourceRef file(String file) {
        return new SourceRef(file, -1, -1);
    }
}
