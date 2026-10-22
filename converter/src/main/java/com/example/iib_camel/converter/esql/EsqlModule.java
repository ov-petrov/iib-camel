package com.example.iib_camel.converter.esql;

import java.util.List;
import java.util.Optional;

/**
 * Промежуточная модель (IR) модуля ESQL — FR-3.1 из docs/functional-requirements.ru.md.
 * Присваивания `SET <target> = <value>;` хранятся как «цель → выражение».
 */
public record EsqlModule(String schema, String name, String type, List<EsqlFunction> functions) {

    public Optional<EsqlFunction> function(String name) {
        return functions.stream().filter(f -> f.name().equals(name)).findFirst();
    }
}
