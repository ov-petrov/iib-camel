package ru.petrovov.iib_camel.converter.esql;

import java.util.List;

public sealed interface EsqlStatement {
    record Assignment(EsqlAssignment assignment) implements EsqlStatement {}
    record Conditional(String condition, List<EsqlStatement> whenTrue,
                       List<EsqlStatement> whenFalse, String source) implements EsqlStatement {}
}
