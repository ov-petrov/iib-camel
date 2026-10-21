package com.example.iib_camel.converter.diagram;

import com.example.iib_camel.converter.msgflow.FlowConnection;
import com.example.iib_camel.converter.msgflow.FlowNode;
import com.example.iib_camel.converter.msgflow.MsgFlow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Рендерер IR → ASCII-диаграмма в виде «дерева-конспекта»
 * (docs/diagram-renderer-design.ru.md, раздел 4.4, решение D-8).
 *
 * Обход в глубину от корней (узлов без входящих связей) в порядке объявления IR;
 * каждая связь — строка-ветка с подписью терминала. Повторная встреча узла
 * (циклы, возвраты) помечается «(↑ показано выше)» и не разворачивается.
 *
 * Глифы соответствуют стилю ASCII-деталей README проекта (box-drawing UTF-8).
 * Детерминированный вывод (D-4): обход и подписи формируются только по упорядоченным данным.
 */
public final class AsciiDiagramRenderer {

    private static final String BRANCH = "├─ ";
    private static final String LAST = "└─ ";
    private static final String VERTICAL = "│  ";
    private static final String BLANK = "   ";
    private static final String ARROW = "→ ";
    private static final String SEEN_MARK = " (↑ показано выше)";

    /** Рендерит диаграмму; порядок строк = порядок в обходе, детерминированный. */
    public String render(MsgFlow flow) {
        Map<String, FlowNode> byId = new LinkedHashMap<>();
        for (FlowNode node : flow.nodes()) {
            byId.put(node.id(), node);
        }
        Map<String, List<FlowConnection>> outgoing = flow.connections().stream()
                .collect(Collectors.groupingBy(FlowConnection::sourceNodeId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<String, List<FlowConnection>> incomingTargets = flow.connections().stream()
                .collect(Collectors.groupingBy(FlowConnection::targetNodeId,
                        LinkedHashMap::new, Collectors.toList()));

        Set<String> seen = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();

        // Корни: узлы без входящих связей, затем остатки циклов (если появились)
        List<FlowNode> roots = flow.nodes().stream()
                .filter(n -> !incomingTargets.containsKey(n.id()))
                .toList();
        for (FlowNode root : roots) {
            out.append(nodeSummary(root)).append('\n');
            seen.add(root.id());
            visit(root, outgoing, byId, seen, out, "");
        }
        for (FlowNode node : flow.nodes()) {
            if (!seen.contains(node.id())) {
                out.append(nodeSummary(node)).append('\n');
                seen.add(node.id());
                visit(node, outgoing, byId, seen, out, "");
            }
        }
        if (out.lastIndexOf("\n") != out.length() - 1) {
            out.append('\n');
        }
        return out.toString();
    }

    private void visit(FlowNode node, Map<String, List<FlowConnection>> outgoing,
                       Map<String, FlowNode> byId, Set<String> seen, StringBuilder out, String prefix) {
        List<FlowConnection> children = outgoing.getOrDefault(node.id(), List.of());
        for (int i = 0; i < children.size(); i++) {
            FlowConnection connection = children.get(i);
            boolean last = i == children.size() - 1;
            FlowNode target = byId.get(connection.targetNodeId());

            out.append(prefix).append(last ? LAST : BRANCH);
            String terminal = connection.sourceTerminal();
            if (!terminal.isBlank()) {
                out.append(terminal).append(' ').append(ARROW);
            }
            if (target == null) {
                out.append("«узел не разрешён: ").append(connection.targetNodeId()).append("»\n");
                continue;
            }
            out.append(nodeSummary(target));
            if (!seen.add(target.id())) {
                out.append(SEEN_MARK);
            } else {
                out.append('\n');
                visit(target, outgoing, byId, seen, out, prefix + (last ? BLANK : VERTICAL));
            }
        }
    }

    /** Однострочное описание узла: kind «label» [самое содержательное свойство] (ср. D-6). */
    private static String nodeSummary(FlowNode node) {
        StringBuilder summary = new StringBuilder();
        summary.append(node.kind());
        String label = labelOf(node);
        if (!label.isBlank()) {
            summary.append(" «").append(label).append('»');
        }
        String caption = captionOf(node.properties());
        if (caption != null) {
            summary.append(" [").append(caption).append(']');
        }
        return summary.toString();
    }

    private static String labelOf(FlowNode node) {
        return node.label().isBlank() ? node.id() : node.label();
    }

    /** Самое содержательное свойство узла (A5): очередь либо ESQL-модуль. */
    private static String captionOf(Map<String, String> properties) {
        String queue = properties.get("queueName");
        if (queue != null && !queue.isBlank()) {
            return queue;
        }
        String expression = properties.get("computeExpression");
        if (expression != null && expression.contains("#")) {
            // esql://routine/adapter#Adapter_Compute1.Main -> Adapter_Compute1.Main
            return expression.substring(expression.indexOf('#') + 1);
        }
        return null;
    }
}
