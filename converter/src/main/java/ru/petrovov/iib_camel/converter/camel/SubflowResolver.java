package ru.petrovov.iib_camel.converter.camel;

import ru.petrovov.iib_camel.converter.msgflow.FlowNode;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlow;

import java.util.LinkedHashSet;
import java.util.Map;

final class SubflowResolver {
    private final Map<String, MsgFlow> registry;

    SubflowResolver(Map<String, MsgFlow> registry) {
        this.registry = registry;
    }

    MsgFlow resolve(FlowNode reference) {
        String requested = name(reference.properties().getOrDefault("subflowName", ""));
        var candidates = new LinkedHashSet<MsgFlow>();
        registry.forEach((key, value) -> {
            if (name(key).equals(requested) || name(value.name()).equals(requested)) {
                candidates.add(value);
            }
        });
        if (candidates.size() > 1) {
            throw new IllegalArgumentException("неоднозначная ссылка на подпоток: " + requested);
        }
        return candidates.isEmpty() ? null : candidates.iterator().next();
    }

    static String name(String name) {
        return name.replaceFirst("(?i)\\.(msgflow|subflow)$", "");
    }
}
