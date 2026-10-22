package com.example.iib_camel.converter.report;

/**
 * Замечание генератора маршрутов об одной единице конвертации (FR-5.2): узел msgflow
 * с найденным статусом и человекочитаемым примечанием (цель элемента Camel или причина TODO).
 * Несколько записей по одному узлу агрегируются в отчёте (худший статус + объединённые примечания).
 */
public record Diagnosis(String nodeId, String kind, UnitStatus status, String note) {
}
