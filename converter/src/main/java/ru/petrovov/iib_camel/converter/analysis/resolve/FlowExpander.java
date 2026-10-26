package ru.petrovov.iib_camel.converter.analysis.resolve;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.petrovov.iib_camel.converter.analysis.model.FlowDocument;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.Diagnostic;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.GraphEdge;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.HandlerReference;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.NodeInstance;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.Severity;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.SubflowInstance;
import ru.petrovov.iib_camel.converter.analysis.model.PropertyValue;
import ru.petrovov.iib_camel.converter.analysis.model.SourceRef;
import ru.petrovov.iib_camel.converter.diagram.NodeCategory;
import ru.petrovov.iib_camel.converter.diagram.NodeCategoryRegistry;
import ru.petrovov.iib_camel.converter.msgflow.FlowNode;

public final class FlowExpander {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 10_000;
    private static final Set<String> HANDLER_KINDS = Set.of("Compute", "Filter", "Database", "JavaCompute", "Mapping");
    private static final List<String> HANDLER_PROPERTIES = List.of("computeExpression", "filterExpression",
            "databaseExpression", "mappingExpression", "javaClass");

    private final SubflowResolver resolver;
    private final NodeCategoryRegistry categories = NodeCategoryRegistry.withIibDefaults();

    public FlowExpander(List<FlowDocument> documents) {
        resolver = new SubflowResolver(documents);
    }

    public Expansion expand(FlowDocument document) {
        State state = new State(document.file());
        expand(document, List.of(), PromotedProperties.parameters(document, Map.of()), Set.of(), state);
        return new Expansion(List.copyOf(state.nodes.values()), state.edges, state.subflows,
                state.handlers, state.diagnostics);
    }

    private Boundary expand(FlowDocument document, List<String> path, Map<String, PropertyValue> parameters,
                              Set<String> ancestors, State state) {
        Set<String> visited = new HashSet<>(ancestors);
        visited.add(document.file());
        Map<String, NodeTarget> targets = new LinkedHashMap<>();
        Map<String, List<String>> inputs = new LinkedHashMap<>();
        Map<String, List<String>> outputs = new LinkedHashMap<>();
        validateLinks(document, state, path);

        for (FlowNode node : document.flow().nodes()) {
            List<String> nodePath = append(path, node.id());
            String id = state.id(nodePath);
            Map<String, PropertyValue> properties = PromotedProperties.node(document, node, parameters);
            SourceRef source = document.nodeSource(node.id());
            if (state.nodes.size() >= MAX_NODES) {
                state.issue(Code.EXPANSION_LIMIT, Severity.ERROR, id, "Превышен предел развёртывания узлов", source);
                break;
            }
            if (node.kind().equals("Subflow")) {
                String reference = properties.getOrDefault("subflowName", PropertyValue.unknown(source)).value();
                List<FlowDocument> matches = resolver.resolve(reference, document);
                if (matches.size() == 1 && !visited.contains(matches.getFirst().file()) && path.size() < MAX_DEPTH) {
                    FlowDocument child = matches.getFirst();
                    Map<String, PropertyValue> childParameters = PromotedProperties.parameters(child, properties);
                    state.subflows.add(new SubflowInstance(id, node.id(), child.file(),
                            safeProperties(childParameters), source));
                    Boundary boundary = expand(child, nodePath, childParameters, visited, state);
                    targets.put(node.id(), new NodeTarget(id, boundary, source));
                    continue;
                }
                Code code = matches.isEmpty() ? Code.MISSING_SUBFLOW : matches.size() > 1
                        ? Code.AMBIGUOUS_SUBFLOW : visited.contains(matches.getFirst().file())
                        ? Code.SUBFLOW_CYCLE : Code.EXPANSION_LIMIT;
                String detail = matches.size() > 1
                        ? "; кандидаты: " + String.join(", ", matches.stream().map(FlowDocument::file).toList()) : "";
                state.issue(code, code == Code.EXPANSION_LIMIT ? Severity.ERROR : Severity.WARNING, id,
                        "Подпоток не раскрыт: " + reference + detail, source);
            }
            state.nodes.put(id, new NodeInstance(id, node.id(), String.join("/", path), node.label(), node.kind(),
                    safeProperties(properties), source));
            targets.put(node.id(), new NodeTarget(id, null, source));
            if (node.kind().equals("Input")) {
                addAliases(inputs, node, id);
            } else if (node.kind().equals("Output")) {
                addAliases(outputs, node, id);
            }
            if (HANDLER_KINDS.contains(node.kind())) {
                String expression = HANDLER_PROPERTIES.stream().map(properties::get).filter(value -> value != null)
                        .map(PropertyValue::value).filter(value -> !value.isBlank()).findFirst().orElse("");
                String dataSource = properties.getOrDefault("dataSource", PropertyValue.unknown(source)).value();
                state.handlers.add(new HandlerReference(id, expression, dataSource, source));
                state.issue(Code.OPAQUE_HANDLER, Severity.INFO, id,
                        "Условия и побочные эффекты обработчика не анализируются", source);
            } else if (!node.kind().equals("Subflow") && !Set.of("KafkaConsumer", "KafkaProducer").contains(node.kind())
                    && categories.categorize(node.kind()) == NodeCategory.OTHER) {
                state.issue(Code.UNKNOWN_NODE_TYPE, Severity.WARNING, id,
                        "Неизвестная семантика типа узла: " + node.kind(), source);
            }
        }

        for (int index = 0; index < document.flow().connections().size(); index++) {
            var edge = document.flow().connections().get(index);
            SourceRef source = index < document.connectionSources().size()
                    ? document.connectionSources().get(index) : document.source();
            NodeTarget from = targets.get(edge.sourceNodeId());
            NodeTarget to = targets.get(edge.targetNodeId());
            if (from == null || to == null) {
                state.issue(Code.EXPANSION_LIMIT, Severity.ERROR, "", "Часть соединений не раскрыта", source);
                continue;
            }
            String sourceId = port(from, edge.sourceTerminal(), false, state);
            String targetId = port(to, edge.targetTerminal(), true, state);
            state.edges.add(new GraphEdge(sourceId, edge.sourceTerminal(), targetId, edge.targetTerminal(), source));
        }
        return new Boundary(inputs, outputs);
    }

    private static String port(NodeTarget target, String terminal, boolean input, State state) {
        if (target.boundary() == null) {
            return target.id();
        }
        Map<String, List<String>> ports = input ? target.boundary().inputs() : target.boundary().outputs();
        List<String> matches = ports.getOrDefault(terminal, List.of());
        if (matches.isEmpty() && terminal.equals(input ? "in" : "out")) {
            List<String> unique = ports.values().stream().flatMap(List::stream).distinct().toList();
            if (unique.size() == 1) {
                matches = unique;
            }
        }
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        String id = target.id() + (input ? "::input:" : "::output:") + encode(terminal);
        if (!state.nodes.containsKey(id)) {
            state.nodes.put(id, new NodeInstance(id, terminal, target.id(), terminal, "UnresolvedTerminal",
                    Map.of(), target.source()));
            state.issue(Code.UNRESOLVED_TERMINAL, Severity.WARNING, id,
                    "Терминал подпотока не разрешён однозначно: " + terminal, target.source());
        }
        return id;
    }

    private static void addAliases(Map<String, List<String>> ports, FlowNode node, String id) {
        Set<String> aliases = new java.util.LinkedHashSet<>();
        aliases.add(node.id());
        aliases.add(node.id().replaceFirst("^(InTerminal|OutTerminal)\\.", ""));
        if (!node.label().isBlank()) {
            aliases.add(node.label());
        }
        for (String alias : aliases) {
            ports.computeIfAbsent(alias, ignored -> new ArrayList<>()).add(id);
        }
    }

    private static void validateLinks(FlowDocument document, State state, List<String> path) {
        Set<String> parameters = new HashSet<>();
        document.properties().forEach(definition -> {
            parameters.add(definition.id());
            parameters.add(definition.name());
        });
        Set<String> nodes = new HashSet<>();
        document.flow().nodes().forEach(node -> nodes.add(node.id()));
        Set<List<String>> targets = new HashSet<>();
        for (var link : document.propertyLinks()) {
            if (!parameters.contains(link.promotedAttribute()) || link.targetProperty().isBlank()
                    || link.overriddenNodeIds().isEmpty() || !nodes.containsAll(link.overriddenNodeIds())) {
                state.issue(Code.INVALID_PROPERTY_LINK, Severity.WARNING, state.id(path),
                        "Неразрешённая привязка продвинутого свойства: " + link.promotedAttribute(), link.source());
            }
            for (String nodeId : link.overriddenNodeIds()) {
                if (!targets.add(List.of(nodeId, link.targetProperty()))) {
                    state.issue(Code.AMBIGUOUS_PROPERTY_LINK, Severity.WARNING, state.id(append(path, nodeId)),
                            "Несколько продвинутых свойств задают одно свойство узла: " + link.targetProperty(), link.source());
                }
            }
        }
    }

    private static Map<String, PropertyValue> safeProperties(Map<String, PropertyValue> properties) {
        Map<String, PropertyValue> safe = new LinkedHashMap<>();
        properties.forEach((key, value) -> {
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            safe.put(key, lower.contains("password") || lower.contains("secret") || lower.contains("token")
                    || lower.contains("authorization")
                    ? new PropertyValue("[REDACTED]", value.origin(), value.sources()) : value);
        });
        return safe;
    }

    private static List<String> append(List<String> path, String nodeId) {
        List<String> result = new ArrayList<>(path);
        result.add(nodeId);
        return result;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public record Expansion(List<NodeInstance> nodes, List<GraphEdge> edges, List<SubflowInstance> subflows,
                              List<HandlerReference> handlers, List<Diagnostic> diagnostics) {
    }

    private record Boundary(Map<String, List<String>> inputs, Map<String, List<String>> outputs) {
    }

    private record NodeTarget(String id, Boundary boundary, SourceRef source) {
    }

    private enum Code {
        MISSING_SUBFLOW,
        AMBIGUOUS_SUBFLOW,
        SUBFLOW_CYCLE,
        EXPANSION_LIMIT,
        UNRESOLVED_TERMINAL,
        INVALID_PROPERTY_LINK,
        AMBIGUOUS_PROPERTY_LINK,
        OPAQUE_HANDLER,
        UNKNOWN_NODE_TYPE
    }

    private static final class State {
        private final String flowId;
        private final Map<String, NodeInstance> nodes = new LinkedHashMap<>();
        private final List<GraphEdge> edges = new ArrayList<>();
        private final List<SubflowInstance> subflows = new ArrayList<>();
        private final List<HandlerReference> handlers = new ArrayList<>();
        private final List<Diagnostic> diagnostics = new ArrayList<>();

        private State(String flowId) {
            this.flowId = flowId;
        }

        private String id(List<String> path) {
            return flowId + "::" + String.join("/", path.stream().map(FlowExpander::encode).toList());
        }

        private void issue(Code code, Severity severity, String nodeId, String message, SourceRef source) {
            diagnostics.add(new Diagnostic(code.name(), severity, flowId, nodeId, message, source));
        }
    }
}
