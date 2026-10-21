package com.example.iib_camel.converter.diagram;

import com.example.iib_camel.converter.msgflow.FlowConnection;
import com.example.iib_camel.converter.msgflow.FlowNode;
import com.example.iib_camel.converter.msgflow.MsgFlow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Рендерер топологии потока (IR) в текстовую диаграмму Mermaid
 * (docs/diagram-renderer-design.ru.md, раздел 4).
 *
 * Детерминированный вывод (D-3, D-4): идентификаторы узлов N1..Nk присваиваются по порядку
 * объявления в IR, случайность и системное время отсутствуют, обход — только по упорядоченным
 * коллекциям. Вывод завершается переводом строки; Viridная колонка indentation — 4 пробела.
 *
 * Правило терминалов (D-5): ребро подписывается и рисуется пунктиром, если терминал источника
 * не «основной». Основные: пустой терминал, "out", перечисленный в dynamicOutTerminals узла-источника
 * (данные IR), а для семейства маршрутизации — стартующие с "match" и "default".
 */
public final class MermaidDiagramRenderer {

    private static final Map<NodeCategory, String> CLASS_DEFS = buildClassDefs();

    /** kind узлов семейства маршрутизации — у них «рабочие» выходы match и default. */
    private static final Set<String> ROUTE_KINDS = Set.of("route", "router", "routetolabel", "filter");

    private final NodeCategoryRegistry registry;

    public MermaidDiagramRenderer(NodeCategoryRegistry registry) {
        this.registry = registry;
    }

    public String render(MsgFlow flow) {
        return render(flow, Orientation.LR);
    }

    public String render(MsgFlow flow, Orientation orientation) {
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, FlowNode> byId = new LinkedHashMap<>();
        Set<NodeCategory> used = new LinkedHashSet<>();
        int counter = 0;
        for (FlowNode node : flow.nodes()) {
            counter++;
            ids.put(node.id(), "N" + counter);
            byId.put(node.id(), node);
            used.add(registry.categorize(node.kind()));
        }

        StringBuilder out = new StringBuilder();
        out.append("flowchart ").append(orientation.name()).append('\n');
        for (FlowNode node : flow.nodes()) {
            out.append("    ").append(ids.get(node.id())).append(nodeShape(node))
               .append(":::").append(classKey(registry.categorize(node.kind()))).append('\n');
        }
        for (FlowConnection connection : flow.connections()) {
            if (!ids.containsKey(connection.sourceNodeId()) || !ids.containsKey(connection.targetNodeId())) {
                continue; // защитный пропуск: парсер IR не допускает «висячих» связей
            }
            FlowNode source = byId.get(connection.sourceNodeId());
            String from = ids.get(connection.sourceNodeId());
            String to = ids.get(connection.targetNodeId());
            if (isPrimaryOutput(source, connection)) {
                out.append("    ").append(from).append(" --> ").append(to).append('\n');
            } else {
                out.append("    ").append(from).append(" -. ").append(MermaidEscaper.escape(
                        connection.sourceTerminal())).append(" .-> ").append(to).append('\n');
            }
        }
        for (NodeCategory category : NodeCategory.values()) {
            if (used.contains(category)) {
                out.append("    classDef ").append(classKey(category)).append(' ')
                   .append(CLASS_DEFS.get(category)).append('\n');
            }
        }
        return out.toString();
    }

    /** Описание узла: прямоугольник, ссылка на подпоток — параллелограмм-«каталог» [[...]]. */
    private String nodeShape(FlowNode node) {
        String label = labelOf(node);
        NodeCategory category = categoryOf(node);
        return category == NodeCategory.REFERENCE
                ? "[[" + label + "]]"
                : "[\"" + label + "\"]";
    }

    private NodeCategory categoryOf(FlowNode node) {
        return registry.categorize(node.kind());
    }

    /** Ярлык узла: имя (c пометкой для подпотоков), затем type, затем кадры-безопасные свойства (D-6). */
    private static String labelOf(FlowNode node) {
        List<String> parts = new ArrayList<>();
        String name = node.label().isBlank() ? node.id() : node.label();
        boolean subflow = node.kind() != null
                && "subflow".equals(node.kind().toLowerCase(Locale.ROOT));
        parts.add(MermaidEscaper.escape(name) + (subflow ? " (subflow)" : ""));
        if (node.kind() != null && !node.kind().isBlank()) {
            parts.add(MermaidEscaper.escape(node.kind()));
        }
        String caption = caption(node.properties());
        if (caption != null) {
            parts.add(MermaidEscaper.escape(caption));
        }
        return String.join("<br/>", parts);
    }

    /**
     * Дополнительная строка ярлыка — самое содержательное свойство узла (A5):
     * очередь для ввода/вывода либо ESQL-модуль для Compute.
     */
    private static String caption(Map<String, String> properties) {
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

    /** Основной ли у источника выходной терминал (см. D-5 в javadoc класса). */
    private static boolean isPrimaryOutput(FlowNode source, FlowConnection connection) {
        String terminal = connection.sourceTerminal();
        String normalized = terminal == null ? "" : terminal.toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || "out".equals(normalized)) {
            return true;
        }
        for (String dynamic : source.dynamicOutTerminals()) {
            if (dynamic != null && dynamic.equalsIgnoreCase(connection.sourceTerminal())) {
                return true;
            }
        }
        String kind = source.kind() == null ? "" : source.kind().toLowerCase(Locale.ROOT);
        return ROUTE_KINDS.contains(kind)
                && (normalized.startsWith("match") || normalized.equals("default"));
    }

    /** Имя classDef в нижнем регистре — вход в {NodeCategory#ordinal()}. */
    private static String classKey(NodeCategory category) {
        return category.name().toLowerCase(Locale.ROOT);
    }

    private static Map<NodeCategory, String> buildClassDefs() {
        Map<NodeCategory, String> defs = new LinkedHashMap<>();
        defs.put(NodeCategory.INPUT, "fill:#e8eaf6,stroke:#3949ab,stroke-width:1px");
        defs.put(NodeCategory.OUTPUT, "fill:#e8f5e9,stroke:#2e7d32,stroke-width:1px");
        defs.put(NodeCategory.PROCESS, "fill:#ede7f6,stroke:#4527a0,stroke-width:1px");
        defs.put(NodeCategory.ROUTE, "fill:#fff3e0,stroke:#e65100,stroke-width:1px");
        defs.put(NodeCategory.ERROR, "fill:#ffebee,stroke:#b71c1c,stroke-width:1px");
        defs.put(NodeCategory.REFERENCE, "fill:#f9fbe7,stroke:#827717,stroke-width:1px");
        defs.put(NodeCategory.OTHER, "fill:#eceff1,stroke:#37474f,stroke-width:1px");
        return Map.copyOf(defs);
    }
}
