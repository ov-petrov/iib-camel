package ru.petrovov.iib_camel.converter.esql;

import java.util.List;
import java.util.Optional;

public record EsqlModule(String schema, String name, String type, List<EsqlFunction> functions) {

    public Optional<EsqlFunction> function(String name) {
        return functions.stream().filter(f -> f.name().equalsIgnoreCase(name)).findFirst();
    }
}
