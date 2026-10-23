package ru.petrovov.iib_camel.converter.diagram;

import java.util.*;

public final class NodeCategoryRegistry {

    private static final String SUBFLOW_KIND = "subflow";

    private final Map<String, NodeCategory> byKind = new HashMap<>();

    public static NodeCategoryRegistry withIibDefaults() {
        NodeCategoryRegistry registry = new NodeCategoryRegistry();
        for (String kind : List.of("MQInput", "HTTPInput", "JMSInput", "FileInput",
                                   "SOAPInput", "TCPInput", "MQGet", "Input")) {
            registry.register(kind, NodeCategory.INPUT);
        }
        for (String kind : List.of("MQOutput", "HTTPReply", "JMSOutput", "FileOutput",
                                   "SOAPReply", "TCPOutput", "MQReply", "Output")) {
            registry.register(kind, NodeCategory.OUTPUT);
        }
        for (String kind : List.of("Filter", "Route", "RouteToLabel", "Label", "Router")) {
            registry.register(kind, NodeCategory.ROUTE);
        }
        for (String kind : List.of("Compute", "JavaCompute", "Mapping", ".NETCompute", "Database",
                                   "Collector", "Aggregate", "Resequence", "Split",
                                   "TimeoutControl", "TimeoutNotify", "SetVariable",
                                   "ResetContentDescriptor", "PASSTHRU", "Trace")) {
            registry.register(kind, NodeCategory.PROCESS);
        }
        for (String kind : List.of("Throw", "TryCatch")) {
            registry.register(kind, NodeCategory.ERROR);
        }
        return registry;
    }

    public void register(String kind, NodeCategory category) {
        byKind.put(normalize(kind), Objects.requireNonNull(category, "category"));
    }

    public NodeCategory categorize(String kind) {
        if (SUBFLOW_KIND.equals(normalize(kind))) {
            return NodeCategory.REFERENCE;
        }
        NodeCategory category = byKind.get(normalize(kind));
        return category != null ? category : NodeCategory.OTHER;
    }

    private static String normalize(String kind) {
        return kind == null ? "" : kind.toLowerCase(Locale.ROOT).trim();
    }
}
