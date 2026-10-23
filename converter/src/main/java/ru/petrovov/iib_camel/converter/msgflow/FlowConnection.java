package ru.petrovov.iib_camel.converter.msgflow;

public record FlowConnection(String sourceNodeId, String sourceTerminal,
                             String targetNodeId, String targetTerminal) {
}
