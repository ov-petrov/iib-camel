package ru.petrovov.iib_camel.converter.report;

public record Diagnosis(String nodeId, String kind, UnitStatus status, String note) {
}
