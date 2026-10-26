package ru.petrovov.iib_camel.converter.analysis.endpoint;

import org.junit.jupiter.api.Test;
import ru.petrovov.iib_camel.converter.analysis.model.EndpointDescriptor;
import ru.petrovov.iib_camel.converter.analysis.model.PropertyValue;
import ru.petrovov.iib_camel.converter.analysis.model.SourceRef;
import ru.petrovov.iib_camel.converter.msgflow.FlowNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.petrovov.iib_camel.converter.analysis.model.EndpointDescriptor.Role;
import static ru.petrovov.iib_camel.converter.analysis.model.PropertyValue.Origin;

class EndpointExtractorTest {
    private static final SourceRef SOURCE = SourceRef.file("Synthetic.msgflow");

    private final EndpointExtractor extractor = new EndpointExtractor();

    @Test
    void extractsMqInputWithExactQueueAndManagerCase() {
        EndpointDescriptor endpoint = extract("MQInput", Map.of(
                "queueName", "Neutral.Inbox",
                "destinationQueueManagerName", "NeutralManager"));

        assertEquals(EndpointDescriptor.Transport.MQ, endpoint.transport());
        assertEquals(EndpointDescriptor.DestinationKind.QUEUE, endpoint.destinationKind());
        assertEquals(Role.CONSUME, endpoint.role());
        assertEquals(PropertyValue.declared("Neutral.Inbox", SOURCE), endpoint.destination());
        assertEquals(PropertyValue.declared("NeutralManager", SOURCE), endpoint.broker());
    }

    @Test
    void mqGetIsFetchAndMentionsPossibleRuntimeOverrides() {
        EndpointDescriptor endpoint = extract("MQGet", Map.of("queueName", "Synthetic.Context"));

        assertEquals(Role.FETCH, endpoint.role());
        assertEquals("Synthetic.Context", endpoint.destination().value());
        assertEquals(Origin.UNKNOWN, endpoint.broker().origin());
        assertTrue(endpoint.note().contains("local environment"));
    }

    @Test
    void absentOutputModeDefaultsToQueueName() {
        EndpointDescriptor endpoint = extract("MQOutput", Map.of(
                "queueName", "Synthetic.Out",
                "queueManagerName", "LegacyManager"));

        assertEquals(Role.PRODUCE, endpoint.role());
        assertEquals("queue", endpoint.mode());
        assertEquals("Synthetic.Out", endpoint.destination().value());
        assertEquals("LegacyManager", endpoint.broker().value());
    }

    @Test
    void unverifiedExplicitQueueModeDoesNotAssumeAStaticDestination() {
        EndpointDescriptor endpoint = extract("MQOutput", Map.of(
                "queueName", "Synthetic.Out", "destinationMode", "queue"));

        assertEquals(Origin.UNKNOWN, endpoint.destination().origin());
    }

    @Test
    void replyAndListOutputModesDoNotUseConfiguredQueueName() {
        for (String mode : List.of("reply", "list")) {
            EndpointDescriptor endpoint = extract("MQOutput", Map.of(
                    "queueName", "Ignored.Queue", "destinationMode", mode));

            assertEquals(mode.equals("list") ? "list" : "unknown", endpoint.mode());
            assertEquals(Origin.UNKNOWN, endpoint.destination().origin());
            assertEquals("", endpoint.destination().value());
            assertFalse(endpoint.note().isBlank());
        }
    }

    @Test
    void unknownOrBlankOutputModeDoesNotFallBackToQueueName() {
        for (String mode : List.of("futureMode", "Queue Name", " ")) {
            EndpointDescriptor endpoint = extract("MQOutput", Map.of(
                    "queueName", "Ignored.Queue", "destinationMode", mode));

            assertEquals(Origin.UNKNOWN, endpoint.destination().origin());
            assertTrue(endpoint.note().contains("destinationMode"));
        }
    }

    @Test
    void unresolvedPromotedOutputModeDoesNotFallBackToDeclaredMode() {
        EndpointDescriptor endpoint = extractor.extract(node("MQOutput", Map.of(
                        "queueName", "Ignored.Queue", "destinationMode", "queue")),
                Map.of("destinationMode", PropertyValue.unknown(SOURCE)), SOURCE).orElseThrow();

        assertEquals(Origin.UNKNOWN, endpoint.destination().origin());
        assertEquals("unknown", endpoint.mode());
    }

    @Test
    void mqReplyAlwaysHasDynamicDestination() {
        EndpointDescriptor endpoint = extract("MQReply", Map.of(
                "queueName", "Ignored.Queue", "replyToQ", "Ignored.Reply"));

        assertEquals(Role.REPLY, endpoint.role());
        assertEquals(Origin.UNKNOWN, endpoint.destination().origin());
        assertTrue(endpoint.note().contains("MQMD"));
    }

    @Test
    void missingQueueManagerIsNeverInferredFromHostOrNodeName() {
        EndpointDescriptor endpoint = extract("MQInput", Map.of(
                "queueName", "Synthetic.In", "queueManagerHostname", "broker.example.invalid"));

        assertEquals(Origin.UNKNOWN, endpoint.broker().origin());
        assertEquals("", endpoint.broker().value());
        assertTrue(endpoint.note().contains("deployment context"));
    }

    @Test
    void unresolvedPolicyAndCcdtPreventQueueManagerIdentityClaims() {
        for (Map<String, String> properties : List.of(
                Map.of("queueName", "Synthetic.In", "destinationQueueManagerName", "ConfiguredManager",
                        "policyUrl", "{SyntheticPolicies}:Endpoint"),
                Map.of("queueName", "Synthetic.In", "destinationQueueManagerName", "ConfiguredManager",
                        "connection", "CCDT"))) {
            EndpointDescriptor endpoint = extract("MQInput", properties);

            assertEquals("Synthetic.In", endpoint.destination().value());
            assertEquals(Origin.UNKNOWN, endpoint.broker().origin());
        }
    }

    @Test
    void differentModernAndLegacyManagersRemainAmbiguous() {
        EndpointDescriptor endpoint = extract("MQOutput", Map.of(
                "queueName", "Synthetic.Out", "destinationQueueManagerName", "ConnectionManager",
                "queueManagerName", "DestinationManager"));

        assertEquals(Origin.UNKNOWN, endpoint.broker().origin());
        assertTrue(endpoint.note().contains("disagree"));
    }

    @Test
    void knownLocalOrClientConnectionRetainsDeclaredManager() {
        for (String connection : List.of("SERVER", "CLIENT")) {
            EndpointDescriptor endpoint = extract("MQInput", Map.of(
                    "queueName", "Synthetic.In", "connection", connection,
                    "destinationQueueManagerName", "SyntheticManager"));

            assertEquals("SyntheticManager", endpoint.broker().value());
        }
    }

    @Test
    void blankAddressesBecomeUnknown() {
        EndpointDescriptor endpoint = extract("MQInput", Map.of(
                "queueName", " \t", "destinationQueueManagerName", ""));

        assertEquals(Origin.UNKNOWN, endpoint.destination().origin());
        assertEquals(Origin.UNKNOWN, endpoint.broker().origin());
    }

    @Test
    void effectivePromotedValuesRetainTheirOriginsAndEvidence() {
        SourceRef parent = SourceRef.file("SyntheticParent.msgflow");
        PropertyValue queue = new PropertyValue("Resolved.MixedCase", Origin.RESOLVED, List.of(SOURCE, parent));
        PropertyValue manager = new PropertyValue("ResolvedManager", Origin.RESOLVED, List.of(parent));
        EndpointDescriptor endpoint = extractor.extract(node("MQInput", Map.of(
                        "queueName", "Declared.Queue", "destinationQueueManagerName", "DeclaredManager")),
                Map.of("queueName", queue, "destinationQueueManagerName", manager), SOURCE).orElseThrow();

        assertSame(queue, endpoint.destination());
        assertSame(manager, endpoint.broker());
    }

    @Test
    void blankOrUnknownEffectiveAddressSuppressesDeclaredValue() {
        for (PropertyValue effective : List.of(
                new PropertyValue("", Origin.RESOLVED, List.of(SOURCE)), PropertyValue.unknown(SOURCE))) {
            EndpointDescriptor endpoint = extractor.extract(node("MQInput", Map.of("queueName", "Declared.Queue")),
                    Map.of("queueName", effective), SOURCE).orElseThrow();

            assertEquals(Origin.UNKNOWN, endpoint.destination().origin());
            assertEquals("", endpoint.destination().value());
        }
    }

    @Test
    void kafkaConsumerAndProducerUseTopicAndBootstrapServerProperties() {
        for (String kind : List.of("KafkaConsumer", "KafkaProducer")) {
            EndpointDescriptor endpoint = extract(kind, Map.of(
                    "topicName", "Synthetic.Topic", "bootstrapServers", "BrokerA.invalid:9092,BrokerB.invalid:9092"));

            assertEquals(EndpointDescriptor.Transport.KAFKA, endpoint.transport());
            assertEquals(EndpointDescriptor.DestinationKind.TOPIC, endpoint.destinationKind());
            assertEquals(kind.equals("KafkaConsumer") ? Role.CONSUME : Role.PRODUCE, endpoint.role());
            assertEquals("Synthetic.Topic", endpoint.destination().value());
            assertEquals("BrokerA.invalid:9092,BrokerB.invalid:9092", endpoint.broker().value());
        }
    }

    @Test
    void kafkaBrokerRequiresExplicitContextAndResolvedPolicy() {
        EndpointDescriptor absent = extract("KafkaConsumer", Map.of("topicName", "Synthetic.Topic"));
        assertEquals(Origin.UNKNOWN, absent.broker().origin());

        for (String key : List.of("policyUrl", "propertiesFilePath")) {
            EndpointDescriptor external = extract("KafkaConsumer", Map.of(
                    "topicName", "Synthetic.Topic", "bootstrapServers", "Broker.invalid:9092", key, "external"));

            assertEquals(Origin.UNKNOWN, external.broker().origin());
        }
    }

    @Test
    void kafkaEffectiveAddressesPreserveResolvedOrigin() {
        PropertyValue topic = new PropertyValue("Resolved.Topic", Origin.RESOLVED, List.of(SOURCE));
        PropertyValue servers = new PropertyValue("Resolved.invalid:9092", Origin.RESOLVED, List.of(SOURCE));
        EndpointDescriptor endpoint = extractor.extract(node("KafkaConsumer", Map.of()),
                Map.of("topicName", topic, "bootstrapServers", servers), SOURCE).orElseThrow();

        assertSame(topic, endpoint.destination());
        assertSame(servers, endpoint.broker());
    }

    @Test
    void unsupportedKindsAndLookalikesAreNotGuessed() {
        for (String kind : List.of("JMSInput", "KafkaRead", "CustomMQInput", "KafkaConsumerVariant", "mqinput")) {
            assertTrue(extractor.extract(node(kind, Map.of()), Map.of(), SOURCE).isEmpty());
        }
    }

    private EndpointDescriptor extract(String kind, Map<String, String> properties) {
        return extractor.extract(node(kind, properties), Map.of(), SOURCE).orElseThrow();
    }

    private FlowNode node(String kind, Map<String, String> properties) {
        return new FlowNode("SyntheticNode", kind, "Synthetic node", properties, List.of());
    }
}
