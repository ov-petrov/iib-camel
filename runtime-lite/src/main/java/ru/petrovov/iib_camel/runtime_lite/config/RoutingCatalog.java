package ru.petrovov.iib_camel.runtime_lite.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RoutingCatalog {

    private final RoutingProperties properties;

    @PostConstruct
    void validate() {
        properties.getRoutes().values().forEach(route -> destination(route.getOutput()));
    }

    public RoutingProperties.Route route(String id) {
        var route = properties.getRoutes().get(id);
        if (route == null) {
            throw new IllegalArgumentException("Unknown route: " + id);
        }
        return route;
    }

    public RoutingProperties.Destination destination(String alias) {
        var destination = properties.getDestinations().get(alias);
        if (destination == null) {
            throw new IllegalArgumentException("Unknown destination: " + alias);
        }
        return destination;
    }
}
