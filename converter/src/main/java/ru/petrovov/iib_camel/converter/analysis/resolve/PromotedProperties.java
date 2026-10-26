package ru.petrovov.iib_camel.converter.analysis.resolve;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.petrovov.iib_camel.converter.analysis.model.FlowDocument;
import ru.petrovov.iib_camel.converter.analysis.model.PropertyValue;
import ru.petrovov.iib_camel.converter.analysis.model.SourceRef;
import ru.petrovov.iib_camel.converter.msgflow.FlowNode;

final class PromotedProperties {
    private PromotedProperties() {
    }

    static Map<String, PropertyValue> parameters(FlowDocument document, Map<String, PropertyValue> overrides) {
        Map<String, PropertyValue> result = new LinkedHashMap<>();
        for (var definition : document.properties()) {
            PropertyValue value = overrides.get(definition.name());
            if (value == null) {
                value = overrides.get(definition.id());
            }
            if (value == null) {
                value = definition.defaultValue() == null
                        ? PropertyValue.unknown(definition.source())
                        : PropertyValue.declared(definition.defaultValue(), definition.source());
            }
            result.put(definition.id(), resolved(value, definition.source()));
            result.putIfAbsent(definition.name(), resolved(value, definition.source()));
        }
        return result;
    }

    static Map<String, PropertyValue> node(FlowDocument document, FlowNode node,
                                           Map<String, PropertyValue> parameters) {
        Map<String, PropertyValue> result = new LinkedHashMap<>();
        SourceRef source = document.nodeSource(node.id());
        node.properties().forEach((key, value) -> result.put(key, PropertyValue.declared(value, source)));
        java.util.Set<String> linked = new java.util.HashSet<>();
        for (var link : document.propertyLinks()) {
            if (!link.overriddenNodeIds().contains(node.id())) {
                continue;
            }
            PropertyValue value = parameters.get(link.promotedAttribute());
            if (value == null) {
                value = PropertyValue.unknown(link.source());
            }
            result.put(link.targetProperty(), linked.add(link.targetProperty())
                    ? resolved(value, link.source()) : PropertyValue.unknown(link.source()));
        }
        return result;
    }

    private static PropertyValue resolved(PropertyValue value, SourceRef source) {
        List<SourceRef> evidence = new ArrayList<>(value.sources());
        if (!evidence.contains(source)) {
            evidence.add(source);
        }
        return new PropertyValue(value.value(), value.origin() == PropertyValue.Origin.UNKNOWN
                ? PropertyValue.Origin.UNKNOWN : PropertyValue.Origin.RESOLVED, evidence);
    }
}
