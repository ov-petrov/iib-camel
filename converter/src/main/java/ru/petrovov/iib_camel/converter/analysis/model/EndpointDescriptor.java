package ru.petrovov.iib_camel.converter.analysis.model;

public record EndpointDescriptor(Transport transport, DestinationKind destinationKind, Role role,
                                 PropertyValue destination, PropertyValue broker, String mode, String note) {
    public enum Transport {
        MQ,
        KAFKA,
        JMS
    }

    public enum DestinationKind {
        QUEUE,
        TOPIC
    }

    public enum Role {
        CONSUME,
        PRODUCE,
        FETCH,
        REPLY
    }
}
