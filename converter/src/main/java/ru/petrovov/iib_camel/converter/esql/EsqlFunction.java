package ru.petrovov.iib_camel.converter.esql;

import java.util.List;

public record EsqlFunction(String name, String returnType, List<EsqlAssignment> assignments,
                           boolean returnsTrue, List<String> unsupportedFragments,
                           List<EsqlStatement> statements) {
    public EsqlFunction(String name, String returnType, List<EsqlAssignment> assignments,
                        boolean returnsTrue, List<String> unsupportedFragments) {
        this(name, returnType, assignments, returnsTrue, unsupportedFragments,
                assignments.stream().<EsqlStatement>map(EsqlStatement.Assignment::new).toList());
    }
}
