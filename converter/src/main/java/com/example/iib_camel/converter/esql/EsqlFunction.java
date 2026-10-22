package com.example.iib_camel.converter.esql;

/**
 * Функция/процедура внутри модуля ESQL (IR, FR-3.1).
 * assignments — присваивания `SET <цель> = <выражение>`; тексты операторов хранятся как есть.
 */
public record EsqlFunction(String name, String returnType, java.util.List<EsqlAssignment> assignments,
                           boolean returnsTrue) {
}
