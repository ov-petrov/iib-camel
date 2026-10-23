package ru.petrovov.iib_camel.converter.schema;

import java.nio.file.Path;

public record SchemaDiagnostic(Path source, String code, String message) {}
