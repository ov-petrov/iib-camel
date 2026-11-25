package ru.petrovov.iib_camel.runtime_lite.enrichment;

import org.springframework.stereotype.Component;
import ru.petrovov.iib_camel.runtime_lite.config.EnrichmentProperties;
import ru.petrovov.iib_camel.runtime_lite.config.RoutingProperties;
import ru.petrovov.iib_camel.runtime_lite.processor.JmsEnrichmentOperation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class EnrichmentCatalog {

    private final EnrichmentProperties properties;
    private final Map<String, JmsEnrichmentOperation<?>> operations;
    private final Set<String> replyQueues;

    EnrichmentCatalog(EnrichmentProperties properties, RoutingProperties routing,
                      List<JmsEnrichmentOperation<?>> operations) {
        this.properties = properties;
        var registered = new HashMap<String, JmsEnrichmentOperation<?>>();
        for (var operation : operations) {
            String key = operation.key();
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("Enrichment operation requires a key");
            }
            if (registered.putIfAbsent(key, operation) != null) {
                throw new IllegalStateException("Duplicate enrichment operation: " + key);
            }
        }
        this.operations = Map.copyOf(registered);
        var occupiedQueues = new HashSet<String>();
        routing.getRoutes().values().forEach(route -> occupiedQueues.add(route.getInputQueue()));
        properties.getOperations().values().forEach(operation -> occupiedQueues.add(operation.getRequestQueue()));
        var replies = new HashSet<String>();
        properties.getOperations().forEach((key, configured) -> {
            operation(key);
            if (occupiedQueues.contains(configured.getReplyQueue())) {
                throw new IllegalStateException("Reply queue overlaps an input or request queue for operation: " + key);
            }
            replies.add(configured.getReplyQueue());
        });
        this.replyQueues = Set.copyOf(replies);
    }

    public Set<String> replyQueues() {
        return replyQueues;
    }

    public void validate(JmsEnrichmentOperation<?> operation) {
        configuration(operation);
    }

    JmsEnrichmentOperation<?> operation(String key) {
        var operation = operations.get(key);
        if (operation == null) {
            throw new IllegalStateException("Unknown enrichment operation: " + key);
        }
        return operation;
    }

    EnrichmentProperties.Operation configuration(JmsEnrichmentOperation<?> operation) {
        String key = Objects.requireNonNull(operation, "enrichment operation").key();
        var configuration = properties.getOperations().get(key);
        if (operation(key) != operation || configuration == null) {
            throw new IllegalStateException("Enrichment operation requires a registered bean and configuration: " + key);
        }
        return configuration;
    }
}
