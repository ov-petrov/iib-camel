package ru.petrovov.iib_camel.converter.mapping;

import ru.petrovov.iib_camel.converter.schema.SchemaDiagnostic;
import java.util.List;

public record MappingResult(GeneratedMapper mapper, List<SchemaDiagnostic> diagnostics) {
    public MappingResult { diagnostics = List.copyOf(diagnostics); }
    public boolean supported() { return mapper != null && diagnostics.isEmpty(); }
}
