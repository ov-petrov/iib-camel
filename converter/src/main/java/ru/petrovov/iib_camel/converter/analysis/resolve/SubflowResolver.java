package ru.petrovov.iib_camel.converter.analysis.resolve;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.petrovov.iib_camel.converter.analysis.model.FlowDocument;

final class SubflowResolver {
    private final Map<String, List<FlowDocument>> qualified = new LinkedHashMap<>();
    private final Map<String, List<FlowDocument>> simple = new LinkedHashMap<>();

    SubflowResolver(List<FlowDocument> documents) {
        documents.stream().filter(FlowDocument::subflow).forEach(document -> {
            add(qualified, normalize(document.file()), document);
            add(qualified, normalize(document.flow().name()), document);
            add(simple, basename(normalize(document.file())), document);
            add(simple, basename(normalize(document.flow().name())), document);
        });
    }

    List<FlowDocument> resolve(String reference, FlowDocument parent) {
        String key = normalize(reference);
        if (key.isBlank()) {
            return List.of();
        }
        List<FlowDocument> exact = qualified.getOrDefault(key, List.of());
        if (!exact.isEmpty()) {
            return List.copyOf(exact);
        }
        int slash = parent.file().lastIndexOf('/');
        if (slash >= 0) {
            String relative = normalize(parent.file().substring(0, slash + 1) + key);
            List<FlowDocument> matches = qualified.getOrDefault(relative, List.of());
            if (!matches.isEmpty()) {
                return List.copyOf(matches);
            }
        }
        return key.contains("/") ? List.of() : List.copyOf(simple.getOrDefault(key, List.of()));
    }

    private static void add(Map<String, List<FlowDocument>> index, String key, FlowDocument document) {
        if (!key.isBlank()) {
            List<FlowDocument> matches = index.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (!matches.contains(document)) {
                matches.add(document);
            }
        }
    }

    private static String basename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String normalize(String value) {
        String result = value.replace('\\', '/').replaceFirst("(?i)\\.subflow$", "");
        while (result.startsWith("./")) {
            result = result.substring(2);
        }
        return result;
    }
}
