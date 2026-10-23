package ru.petrovov.iib_camel.runtime.transport;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.petrovov.iib_camel.runtime.message.MessageHeader;
import ru.petrovov.iib_camel.runtime.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime.transport.config.RuntimeTransportAutoConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "IIB_TEST_KAFKA_BOOTSTRAP_SERVERS", matches = ".+")
class KafkaTransportIntegrationTest {
    @Test void productionSenderReceivesAcknowledgementAndPreservesDuplicateHeaders() throws Exception {
        String brokers = System.getenv("IIB_TEST_KAFKA_BOOTSTRAP_SERVERS");
        UUID eventId = UUID.randomUUID();
        String topic = "synthetic.runtime.sender." + eventId;
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", brokers))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            try {
                new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RuntimeTransportAutoConfiguration.class))
                        .withPropertyValues("iib.transports.artemis.enabled=false", "iib.transports.ibmmq.enabled=false",
                                "iib.transports.kafka.enabled=true", "iib.transports.kafka.bootstrap-servers=" + brokers)
                        .run(context -> {
                            assertThat(context).hasNotFailed();
                            byte[] payload = {0, 1, (byte) 255, 2};
                            var outbound = new OutboundMessage(eventId, "kafka", topic, payload,
                                    List.of(MessageHeader.text("label", "one"), MessageHeader.text("label", "two")), "synthetic-key");
                            context.getBean("kafkaTransportSender", TransportSender.class).send(outbound);
                            var settings = Map.<String, Object>of("bootstrap.servers", brokers,
                                    "group.id", "synthetic-runtime-" + UUID.randomUUID(), "enable.auto.commit", false,
                                    "auto.offset.reset", "earliest");
                            try (var consumer = new KafkaConsumer<byte[], byte[]>(settings, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
                                var partition = new TopicPartition(topic, 0);
                                consumer.assign(List.of(partition));
                                consumer.seekToBeginning(List.of(partition));
                                var records = new ArrayList<ConsumerRecord<byte[], byte[]>>();
                                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                                while (records.isEmpty() && System.nanoTime() < deadline) {
                                    consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                                }
                                assertThat(records).hasSize(1);
                                var received = records.getFirst();
                                assertThat(received.value()).containsExactly(payload);
                                assertThat(new String(received.key(), StandardCharsets.UTF_8)).isEqualTo("synthetic-key");
                                assertThat(received.headers().headers("label")).hasSize(2);
                                assertThat(new String(received.headers().lastHeader("applicationEventId").value(), StandardCharsets.UTF_8)).isEqualTo(eventId.toString());
                            }
                        });
            } finally {
                admin.deleteTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS);
            }
        }
    }
}
