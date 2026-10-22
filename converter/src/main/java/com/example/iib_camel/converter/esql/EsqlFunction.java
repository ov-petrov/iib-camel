package com.example.iib_camel.converter.esql;

import java.util.List;
/** Функция ESQL: SET вне блоков и исходные фрагменты неподдерживаемой логики. */
public record EsqlFunction(String name, String returnType, List<EsqlAssignment> assignments,
                           boolean returnsTrue, List<String> unsupportedFragments) {
}
