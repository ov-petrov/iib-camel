package com.example.iib_camel.converter.esql;

import java.util.List;
/**
 * Функция/процедура внутри модуля ESQL (IR, FR-3.1).
 * assignments — присваивания `SET <цель> = <выражение>`; тексты операторов хранятся как есть.
 * unsupportedFragments — операторы/конструкции вне базового подмножества
 * (IF/WHILE/CALL/THROW, функции кроме Main) — источник честного статуса отчёта (FR-3.3).
 */
public record EsqlFunction(String name, String returnType, List<EsqlAssignment> assignments,
                           boolean returnsTrue, List<String> unsupportedFragments) {
}
