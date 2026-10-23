package ru.petrovov.iib_camel.runtime.transport;

import jakarta.jms.BytesMessage;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jms.core.JmsTemplate;
import ru.petrovov.iib_camel.runtime.message.MessageHeader;
import ru.petrovov.iib_camel.runtime.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime.transport.config.RuntimeTransportAutoConfiguration;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "IIB_TEST_ARTEMIS_URL", matches = ".+")
class ArtemisTransportIntegrationTest {
    @Test
    void productionSenderCommitsPersistentBinaryMessageToBroker() {
        var settings = new ArrayList<String>();
        settings.add("iib.transports.artemis.enabled=true");
        settings.add("iib.transports.ibmmq.enabled=false");
        settings.add("iib.transports.kafka.enabled=false");
        settings.add("iib.transports.artemis.broker-url=" + System.getenv("IIB_TEST_ARTEMIS_URL"));
        optional(settings, "username", "IIB_TEST_ARTEMIS_USERNAME");
        optional(settings, "password", "IIB_TEST_ARTEMIS_PASSWORD");
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RuntimeTransportAutoConfiguration.class))
            .withPropertyValues(settings.toArray(String[]::new))
            .run(context -> {
                    assertThat(context).hasNotFailed();
                    UUID eventId = UUID.randomUUID();
                    String destination = "synthetic.runtime.sender." + eventId;
                    byte[] payload = {0, 1, (byte) 255, 2};
                var outbound = new OutboundMessage(
                    eventId,
                    "artemis",
                    destination,
                    payload,
                    List.of(
                        MessageHeader.text("label", "synthetic"),
                        MessageHeader.text("JMSCorrelationID", "correlation"),
                        new MessageHeader(
                            "count",
                            "INT",
                            ByteBuffer.allocate(4).putInt(17).array())),
                    null);
                    context.getBean("artemisTransportSender", TransportSender.class).send(outbound);
                    var reader = new JmsTemplate(context.getBean("artemisConnectionFactory", ConnectionFactory.class));
                    reader.setReceiveTimeout(5000);
                    var received = reader.receive(destination);
                    assertThat(received).isInstanceOf(BytesMessage.class);
                    var bytes = (BytesMessage) received;
                    byte[] actual = new byte[(int) bytes.getBodyLength()];
                    bytes.readBytes(actual);
                    assertThat(actual).containsExactly(payload);
                    assertThat(bytes.getJMSDeliveryMode()).isEqualTo(DeliveryMode.PERSISTENT);
                    assertThat(bytes.getStringProperty("applicationEventId")).isEqualTo(eventId.toString());
                    assertThat(bytes.getStringProperty("label")).isEqualTo("synthetic");
                    assertThat(bytes.getObjectProperty("count")).isEqualTo(17);
                    assertThat(bytes.getJMSCorrelationID()).isEqualTo("correlation");
                });
    }

    private static void optional(List<String> settings, String property, String environment) {
        String value = System.getenv(environment);
        if (value != null) {
            settings.add("iib.transports.artemis." + property + "=" + value);
        }
    }
}
