package ru.petrovov.iib_camel.runtime_lite.transport;

import org.junit.jupiter.api.Test;
import ru.petrovov.iib_camel.runtime_lite.config.soap.SoapProperties;
import ru.petrovov.iib_camel.runtime_lite.config.transport.ListenerProperties;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.soap.SoapProcessingException;
import ru.petrovov.iib_camel.runtime_lite.soap.SoapService;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JmsPayloadCodecTest {

    private static final String DESTINATION = "SAMPLE.SOAP";
    private static final String PAYLOAD = "<item>Привет &amp; мир</item>";

    private final SoapProperties properties = new SoapProperties();
    private final ListenerProperties limits = new ListenerProperties();
    private final SoapService soap = new SoapService(32000, 32, 1000);
    private final JmsPayloadCodec codec = new JmsPayloadCodec(properties, soap, new JmsMessageReader(limits));

    @Test
    void preservesMessagesForExcludedPlainJmsDestinations() {
        properties.setPlainJmsDestinations(Set.of(DESTINATION));
        var output = OutboundMessage.of(Transport.JMS, DESTINATION, "plain text");
        var input = new InboundMessage("synthetic", "plain text", Map.of());

        assertThat(codec.formatFor(DESTINATION)).isEqualTo(JmsPayloadFormat.PLAIN);
        assertThat(codec.encode(output)).isSameAs(output);
        assertThat(codec.decode(input, JmsPayloadFormat.PLAIN)).isSameAs(input);
    }

    @Test
    void wrapsAllJmsDestinationsByDefaultAndPreservesMetadata() {
        var output = new OutboundMessage(Transport.JMS, DESTINATION, PAYLOAD, "synthetic-key",
            Map.of(MessageHeaders.CORRELATION_ID, "synthetic-correlation", "origin", "synthetic"));

        var wire = codec.encode(output);

        assertThat(codec.formatFor(DESTINATION)).isEqualTo(JmsPayloadFormat.SOAP_11);
        assertThat(wire.destination()).isEqualTo(output.destination());
        assertThat(wire.headers()).isEqualTo(output.headers());
        assertThat(wire.key()).isEqualTo(output.key());
        assertThat(wire.body()).contains("Envelope");
        var raw = new InboundMessage("synthetic", wire.body(), wire.headers());
        var decoded = codec.decode(raw, JmsPayloadFormat.SOAP_11);
        assertThat(decoded.id()).isEqualTo(raw.id());
        assertThat(decoded.headers()).isEqualTo(raw.headers());
        assertThat(decoded.body()).contains("Привет &amp; мир</item>").doesNotContain("Envelope", "Body>");
        assertThat(codec.encode(OutboundMessage.of(Transport.KAFKA, DESTINATION, PAYLOAD)).body()).isEqualTo(PAYLOAD);
        assertThat(codec.encode(OutboundMessage.of(Transport.JMS, "SAMPLE.OTHER", PAYLOAD)).body()).contains("Envelope");
        properties.setPlainJmsDestinations(Set.of("SAMPLE.PLAIN"));
        assertThat(codec.encode(OutboundMessage.of(Transport.JMS, "SAMPLE.PLAIN", PAYLOAD)).body()).isEqualTo(PAYLOAD);
    }

    @Test
    void usesStoredFormatWhenDestinationConfigurationChanges() {
        var format = codec.formatFor(DESTINATION);
        var raw = new InboundMessage("synthetic", soap.wrap(PAYLOAD), Map.of());
        properties.setPlainJmsDestinations(Set.of(DESTINATION));

        assertThat(codec.formatFor(DESTINATION)).isEqualTo(JmsPayloadFormat.PLAIN);
        assertThat(codec.decode(raw, format).body()).doesNotContain("Envelope");
        var oldFormat = codec.formatFor(DESTINATION);
        properties.setPlainJmsDestinations(Set.of());
        assertThat(codec.formatFor(DESTINATION)).isEqualTo(JmsPayloadFormat.SOAP_11);
        assertThat(codec.decode(new InboundMessage("old", PAYLOAD, Map.of()), oldFormat).body())
            .isEqualTo(PAYLOAD);
    }

    @Test
    void rejectsAnUnwrappedDocumentOnDefaultJmsInput() {
        var input = new InboundMessage("synthetic", PAYLOAD, Map.of());

        assertThatThrownBy(() -> codec.decode(input, codec.formatFor(DESTINATION)))
            .isInstanceOf(SoapProcessingException.class).hasMessageContaining("SOAP 1.1 Envelope");
    }

    @Test
    void checksCompleteUtf8WireSizeIncludingEnvelope() {
        String payload = "<item>" + "я".repeat(40) + "</item>";
        String envelope = soap.wrap(payload);
        int wireBytes = envelope.getBytes(StandardCharsets.UTF_8).length;
        limits.setMaxBodyBytes(wireBytes - 1);
        assertThat(payload.getBytes(StandardCharsets.UTF_8).length).isLessThan(limits.getMaxBodyBytes());
        assertThat(envelope.length()).isLessThan(limits.getMaxBodyBytes());

        assertThatThrownBy(() -> codec.encode(OutboundMessage.of(Transport.JMS, DESTINATION, payload)))
            .isInstanceOf(SoapProcessingException.class).hasMessageContaining("size limit");
        assertThatThrownBy(() -> codec.decode(new InboundMessage("synthetic", envelope, Map.of()),
            JmsPayloadFormat.SOAP_11)).isInstanceOf(SoapProcessingException.class);
        limits.setMaxBodyBytes(wireBytes);
        assertThat(codec.encode(OutboundMessage.of(Transport.JMS, DESTINATION, payload)).body()).isEqualTo(envelope);
    }
}
