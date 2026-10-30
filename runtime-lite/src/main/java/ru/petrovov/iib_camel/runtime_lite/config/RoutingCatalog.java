package ru.petrovov.iib_camel.runtime_lite.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RoutingCatalog {

    private final RoutingProperties properties;

    public RoutingProperties.Route route(String id) {
        var route = properties.getRoutes().get(id);
        if (route == null) {
            throw new IllegalArgumentException("Unknown route: " + id);
        }
        return route;
    }
}
