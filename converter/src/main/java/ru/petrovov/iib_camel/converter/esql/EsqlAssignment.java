package ru.petrovov.iib_camel.converter.esql;

/**
 * Присваивание `SET <цель> = <выражение>;` (IR, FR-3.1).
 */
public record EsqlAssignment(String target, String value) {
}
