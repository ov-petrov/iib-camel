package ru.petrovov.iib_camel.converter.report;

import java.util.List;

public record RouteResult(String xml, List<Diagnosis> diagnoses) {

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
