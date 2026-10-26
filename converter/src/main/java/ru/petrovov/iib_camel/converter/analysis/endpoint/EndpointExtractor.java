package ru.petrovov.iib_camel.converter.analysis.endpoint;

import ru.petrovov.iib_camel.converter.analysis.model.EndpointDescriptor;
import ru.petrovov.iib_camel.converter.analysis.model.PropertyValue;
import ru.petrovov.iib_camel.converter.analysis.model.SourceRef;
import ru.petrovov.iib_camel.converter.msgflow.FlowNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ru.petrovov.iib_camel.converter.analysis.model.EndpointDescriptor.DestinationKind;
import static ru.petrovov.iib_camel.converter.analysis.model.EndpointDescriptor.Role;
import static ru.petrovov.iib_camel.converter.analysis.model.EndpointDescriptor.Transport;
import static ru.petrovov.iib_camel.converter.analysis.model.PropertyValue.Origin;

public final class EndpointExtractor {
    private static final String QUEUE_NAME = "queueName";
    private static final String QUEUE_MANAGER_NAME = "queueManagerName";
    private static final String DESTINATION_QUEUE_MANAGER_NAME = "destinationQueueManagerName";
    private static final String DESTINATION_MODE = "destinationMode";
    private static final String TOPIC_NAME = "topicName";
    private static final String BOOTSTRAP_SERVERS = "bootstrapServers";
    private static final String POLICY_URL = "policyUrl";
    private static final String CONNECTION = "connection";
    private static final String SERVER = "SERVER";
    private static final String CLIENT = "CLIENT";
    private static final String CCDT = "CCDT";
    private static final String PROPERTIES_FILE_PATH = "propertiesFilePath";
    private static final String QUEUE_MODE = "queue";
    private static final String REPLY_MODE = "reply";
    private static final String LIST_MODE = "list";
    private static final String UNKNOWN_MODE = "unknown";
    private static final String TOPIC_MODE = "topic";

    private static final Map<String, NodeType> NODE_TYPES = Map.of(
            "MQInput", new NodeType(Transport.MQ, Role.CONSUME),
            "MQGet", new NodeType(Transport.MQ, Role.FETCH),
            "MQOutput", new NodeType(Transport.MQ, Role.PRODUCE),
            "MQReply", new NodeType(Transport.MQ, Role.REPLY),
            "KafkaConsumer", new NodeType(Transport.KAFKA, Role.CONSUME),
            "KafkaProducer", new NodeType(Transport.KAFKA, Role.PRODUCE));

    public Optional<EndpointDescriptor> extract(FlowNode node, Map<String, PropertyValue> effectiveProperties,
                                                SourceRef source) {
        NodeType type = NODE_TYPES.get(node.kind());
        if (type == null) {
            return Optional.empty();
        }
        Properties properties = new Properties(node.properties(), effectiveProperties, source);
        return Optional.of(type.transport() == Transport.MQ
                ? mqEndpoint(type, properties)
                : kafkaEndpoint(type, properties));
    }

    private EndpointDescriptor mqEndpoint(NodeType type, Properties properties) {
        List<String> notes = new ArrayList<>();
        PropertyValue destination;
        String mode = QUEUE_MODE;
        if (type.role() == Role.REPLY) {
            mode = REPLY_MODE;
            destination = properties.unknown();
            notes.add("Reply destination is supplied by MQMD at run time");
        } else if (type.role() == Role.PRODUCE) {
            mode = outputMode(properties);
            destination = QUEUE_MODE.equals(mode) ? properties.value(QUEUE_NAME) : properties.unknown(DESTINATION_MODE);
            if (REPLY_MODE.equals(mode)) {
                notes.add("Reply destination is supplied by MQMD at run time");
            } else if (LIST_MODE.equals(mode)) {
                notes.add("Destination list is supplied by the local environment at run time");
            } else if (!QUEUE_MODE.equals(mode)) {
                notes.add("Unsupported or unresolved destinationMode; queueName is not assumed");
            }
        } else {
            destination = properties.value(QUEUE_NAME);
        }
        if (destination.origin() == Origin.UNKNOWN && QUEUE_MODE.equals(mode)) {
            notes.add("Queue name is missing or unresolved");
        }
        PropertyValue broker = mqBroker(properties, notes);
        if (type.role() == Role.FETCH) {
            notes.add("Configured MQGet destination can be overridden by the local environment");
        }
        return new EndpointDescriptor(Transport.MQ, DestinationKind.QUEUE, type.role(), destination, broker,
                mode, String.join("; ", notes));
    }

    private String outputMode(Properties properties) {
        if (!properties.contains(DESTINATION_MODE)) {
            return QUEUE_MODE;
        }
        PropertyValue mode = properties.value(DESTINATION_MODE);
        return mode.origin() != Origin.UNKNOWN && LIST_MODE.equals(mode.value()) ? LIST_MODE : UNKNOWN_MODE;
    }

    private PropertyValue mqBroker(Properties properties, List<String> notes) {
        if (properties.configured(POLICY_URL)) {
            notes.add("MQEndpoint policy is not resolved; queue manager identity is unknown");
            return properties.unknown(POLICY_URL);
        }
        if (properties.configured(CONNECTION)) {
            PropertyValue connection = properties.value(CONNECTION);
            if (connection.origin() == Origin.UNKNOWN || CCDT.equals(connection.value())) {
                notes.add("MQ connection requires unresolved run time context");
                return properties.unknown(CONNECTION);
            }
            if (!List.of(SERVER, CLIENT).contains(connection.value())) {
                notes.add("Unsupported MQ connection type; queue manager identity is unknown");
                return properties.unknown(CONNECTION);
            }
        }
        PropertyValue modern = properties.value(DESTINATION_QUEUE_MANAGER_NAME);
        PropertyValue legacy = properties.value(QUEUE_MANAGER_NAME);
        if (properties.configured(DESTINATION_QUEUE_MANAGER_NAME)) {
            if (legacy.origin() != Origin.UNKNOWN && !legacy.value().equals(modern.value())) {
                notes.add("Queue manager properties disagree; queue manager identity is unknown");
                return properties.unknown(DESTINATION_QUEUE_MANAGER_NAME, QUEUE_MANAGER_NAME);
            }
            return modern;
        }
        if (legacy.origin() != Origin.UNKNOWN) {
            return legacy;
        }
        notes.add("Queue manager is not specified; deployment context is required");
        return modern;
    }

    private EndpointDescriptor kafkaEndpoint(NodeType type, Properties properties) {
        List<String> notes = new ArrayList<>();
        PropertyValue destination = properties.value(TOPIC_NAME);
        PropertyValue broker = properties.value(BOOTSTRAP_SERVERS);
        if (properties.configured(POLICY_URL) || properties.configured(PROPERTIES_FILE_PATH)) {
            broker = properties.unknown(POLICY_URL, PROPERTIES_FILE_PATH);
            notes.add("Kafka policy or properties file is not resolved; bootstrap servers are unknown");
        }
        if (destination.origin() == Origin.UNKNOWN) {
            notes.add("Topic name is missing or unresolved");
        }
        if (broker.origin() == Origin.UNKNOWN && notes.isEmpty()) {
            notes.add("Bootstrap servers are missing or unresolved");
        }
        if (type.role() == Role.PRODUCE) {
            notes.add("Configured Kafka topic can be overridden by the local environment");
        }
        return new EndpointDescriptor(Transport.KAFKA, DestinationKind.TOPIC, type.role(), destination, broker,
                TOPIC_MODE, String.join("; ", notes));
    }

    private record NodeType(Transport transport, Role role) {
    }

    private record Properties(Map<String, String> declared, Map<String, PropertyValue> effective, SourceRef source) {
        boolean contains(String key) {
            return effective.containsKey(key) || declared.containsKey(key);
        }

        boolean configured(String key) {
            if (effective.containsKey(key)) {
                PropertyValue value = effective.get(key);
                return value.origin() == Origin.UNKNOWN || !blank(value.value());
            }
            return !blank(declared.get(key));
        }

        PropertyValue value(String key) {
            PropertyValue value = effective.get(key);
            if (value == null) {
                String raw = declared.get(key);
                return blank(raw) ? unknown() : PropertyValue.declared(raw, source);
            }
            if (value.origin() == Origin.UNKNOWN || blank(value.value())) {
                return new PropertyValue("", Origin.UNKNOWN, value.sources());
            }
            return value;
        }

        PropertyValue unknown(String... keys) {
            List<SourceRef> sources = new ArrayList<>();
            for (String key : keys) {
                if (contains(key)) {
                    sources.addAll(value(key).sources());
                }
            }
            if (sources.isEmpty()) {
                sources.add(source);
            }
            return new PropertyValue("", Origin.UNKNOWN, sources.stream().distinct().toList());
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
