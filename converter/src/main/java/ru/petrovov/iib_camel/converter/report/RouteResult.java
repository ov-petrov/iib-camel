package ru.petrovov.iib_camel.converter.report;

import java.util.List;

/**
 * Результат генерации маршрута: XML-текст + полный список диагнозов по узлам
 * отчёта (FR-5.1/5.2). Диагнозы покрывают все узлы входного потока, включая
 * не подключённые к маршруту.
 */
public record RouteResult(String xml, List<Diagnosis> diagnoses) {

    /** Худший статус узла из списка диагнозов (или CONVERTED, если диагнозов нет). */
    public UnitStatus statusOf(String nodeId) {
        UnitStatus status = UnitStatus.CONVERTED;
        for (Diagnosis d : diagnoses) {
            if (d.nodeId().equals(nodeId)) {
                status = UnitStatus.worst(status, d.status());
            }
        }
        return status;
    }
}
