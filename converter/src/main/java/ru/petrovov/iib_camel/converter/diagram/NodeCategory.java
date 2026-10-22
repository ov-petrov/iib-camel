package ru.petrovov.iib_camel.converter.diagram;

/**
 * Категория узла для стилизации диаграммы (docs/diagram-renderer-design.ru.md, раздел 4.2).
 * Внутренняя деталь рендерера: категория вычисляется из IR-атрибута kind.
 */
public enum NodeCategory {
    INPUT,
    OUTPUT,
    PROCESS,
    ROUTE,
    ERROR,
    REFERENCE,
    OTHER
}
