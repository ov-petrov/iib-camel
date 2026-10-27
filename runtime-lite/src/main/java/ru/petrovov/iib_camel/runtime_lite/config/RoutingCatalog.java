package ru.petrovov.iib_camel.runtime_lite.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.petrovov.iib_camel.runtime_lite.transport.BrokerIds;
import ru.petrovov.iib_camel.runtime_lite.transport.BrokerRegistry;

@Component
@RequiredArgsConstructor
public class RoutingCatalog {

    private final RoutingProperties properties;
    private final BrokerRegistry brokers;

    @PostConstruct
    void validate() {
        properties.getDestinations().values().forEach(this::broker);
        properties.getRoutes().values().forEach(route -> {
            brokers.requireJms(route.getBroker());
            destination(route.getOutput());
        });
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

    public String broker(RoutingProperties.Destination destination) {
        String broker = destination.getBroker();
        if (broker == null) {
            broker = switch (destination.getTransport()) {
                case JMS -> BrokerIds.DEFAULT_MQ;
                case KAFKA -> BrokerIds.DEFAULT_KAFKA;
            };
        }
        switch (destination.getTransport()) {
            case JMS -> brokers.requireJms(broker);
            case KAFKA -> brokers.requireKafka(broker);
        }
        return broker;
    }
}
