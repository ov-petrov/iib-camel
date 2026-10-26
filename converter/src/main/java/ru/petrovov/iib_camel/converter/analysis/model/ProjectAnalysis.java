package ru.petrovov.iib_camel.converter.analysis.model;

import java.util.List;
import java.util.Map;

public record ProjectAnalysis(int schemaVersion, List<FlowAnalysis> flows, List<EndpointUsage> endpoints,
                               List<FlowLink> links, List<Diagnostic> diagnostics, List<String> skippedEsqlFiles) {
    public ProjectAnalysis {
        flows = List.copyOf(flows);
        endpoints = List.copyOf(endpoints);
        links = List.copyOf(links);
        diagnostics = List.copyOf(diagnostics);
        skippedEsqlFiles = List.copyOf(skippedEsqlFiles);
    }

    public record FlowAnalysis(String id, String file, String name, boolean subflow,
                                List<NodeInstance> nodes, List<GraphEdge> edges,
                                List<SubflowInstance> subflows, List<HandlerReference> handlers,
                                List<String> endpointIds, List<Diagnostic> diagnostics) {
        public FlowAnalysis {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
            subflows = List.copyOf(subflows);
            handlers = List.copyOf(handlers);
            endpointIds = List.copyOf(endpointIds);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record NodeInstance(String id, String nodeId, String instancePath, String label, String kind,
                                Map<String, PropertyValue> properties, SourceRef source) {
        public NodeInstance {
            properties = Map.copyOf(properties);
        }
    }

    public record GraphEdge(String sourceId, String sourceTerminal, String targetId,
                              String targetTerminal, SourceRef source) {
    }

    public record SubflowInstance(String id, String nodeId, String definitionFile,
                                   Map<String, PropertyValue> parameters, SourceRef source) {
        public SubflowInstance {
            parameters = Map.copyOf(parameters);
        }
    }

    public record HandlerReference(String nodeInstanceId, String expression, String dataSource, SourceRef source) {
    }

    public record EndpointUsage(String id, String flowId, String nodeInstanceId,
                                  EndpointDescriptor endpoint, Reachability reachability, SourceRef source) {
    }

    public record FlowLink(String producerFlowId, String producerEndpointId, String consumerFlowId,
                            String consumerEndpointId, LinkConfidence confidence, String reason) {
    }

    public record Diagnostic(String code, Severity severity, String flowId, String nodeInstanceId,
                               String message, SourceRef source) {
    }

    public enum Reachability {
        STRUCTURAL_NORMAL,
        STRUCTURAL_ERROR,
        STRUCTURAL_MIXED,
        UNCONNECTED,
        UNKNOWN
    }

    public enum LinkConfidence {
        CONFIGURED,
        CANDIDATE
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
