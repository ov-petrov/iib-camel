package ru.petrovov.iib_camel.converter.msgflow;

import java.util.List;

public record MsgFlow(String name, List<FlowNode> nodes, List<FlowConnection> connections) {

    public FlowNode node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("узел не найден: " + id));
    }
}
