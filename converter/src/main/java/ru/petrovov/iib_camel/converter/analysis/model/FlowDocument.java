package ru.petrovov.iib_camel.converter.analysis.model;

import java.util.List;
import java.util.Map;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlow;

public record FlowDocument(String file, boolean subflow, MsgFlow flow, SourceRef source,
                           Map<String, SourceRef> nodeSources, List<SourceRef> connectionSources,
                           List<PropertyDefinition> properties, List<PropertyLink> propertyLinks) {
    public FlowDocument {
        nodeSources = Map.copyOf(nodeSources);
        connectionSources = List.copyOf(connectionSources);
        properties = List.copyOf(properties);
        propertyLinks = List.copyOf(propertyLinks);
    }

    public SourceRef nodeSource(String nodeId) {
        return nodeSources.getOrDefault(nodeId, source);
    }

    public record PropertyDefinition(String id, String name, String defaultValue, SourceRef source) {
    }

    public record PropertyLink(String promotedAttribute, List<String> overriddenNodeIds,
                               String targetProperty, SourceRef source) {
        public PropertyLink {
            overriddenNodeIds = List.copyOf(overriddenNodeIds);
        }
    }
}
