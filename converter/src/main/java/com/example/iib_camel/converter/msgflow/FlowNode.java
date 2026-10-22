package com.example.iib_camel.converter.msgflow;

import java.util.List;
import java.util.Map;

public record FlowNode(String id, String kind, String label, Map<String, String> properties,
                       List<String> dynamicOutTerminals, List<RouteRule> routeRules) {
    public FlowNode {
        routeRules = List.copyOf(routeRules);
    }

    public FlowNode(String id, String kind, String label, Map<String, String> properties,
                    List<String> dynamicOutTerminals) {
        this(id, kind, label, properties, dynamicOutTerminals,
                properties.containsKey("filterTable.filterPattern")
                        ? List.of(new RouteRule(properties.get("filterTable.filterPattern"),
                                properties.getOrDefault("filterTable.routingOutputTerminal", "")))
                        : List.of());
    }
}
