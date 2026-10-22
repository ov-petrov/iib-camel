package ru.petrovov.iib_camel.converter.msgflow;

/** Связь «выходной терминал → входной терминал» между узлами потока. */
public record FlowConnection(String sourceNodeId, String sourceTerminal,
                             String targetNodeId, String targetTerminal) {
}
