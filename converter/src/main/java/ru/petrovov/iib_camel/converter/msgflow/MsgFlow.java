package ru.petrovov.iib_camel.converter.msgflow;

import java.util.List;
import java.util.Map;

/**
 * Промежуточная модель (IR) потока сообщений — FR-1.4 из docs/functional-requirements.ru.md.
 * Терминалы "out"/"catch"/... нормализованы без префиксов OutTerminal./InTerminal..
 */
public record MsgFlow(String name, List<FlowNode> nodes, List<FlowConnection> connections) {

    public FlowNode node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("узел не найден: " + id));
    }
}
