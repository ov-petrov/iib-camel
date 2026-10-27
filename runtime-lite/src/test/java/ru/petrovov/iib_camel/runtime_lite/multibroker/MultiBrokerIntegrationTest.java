package ru.petrovov.iib_camel.runtime_lite.multibroker;

import jakarta.jms.JMSContext;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.petrovov.iib_camel.runtime_lite.RuntimeLiteApplication;
import ru.petrovov.iib_camel.runtime_lite.jdbc.DeliveryTables;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.MessagePayload;
import ru.petrovov.iib_camel.runtime_lite.outbox.MessagePublisher;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxDispatcher;
import ru.petrovov.iib_camel.runtime_lite.processor.MessageProcessor;
import ru.petrovov.iib_camel.runtime_lite.transport.BrokerRegistry;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsMessageReader;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = RuntimeLiteApplication.class, properties = {
    "runtime-lite.outbox.scheduling-enabled=false",
    "runtime-lite.routes.route-a.broker=mq-a",
    "runtime-lite.routes.route-a.processor=processorA",
    "runtime-lite.routes.route-a.output=kafka-output-a",
    "runtime-lite.routes.route-b.broker=mq-b",
    "runtime-lite.routes.route-b.processor=processorB",
    "runtime-lite.routes.route-b.output=kafka-output-b",
    "runtime-lite.destinations.mq-output-a.transport=JMS",
    "runtime-lite.destinations.mq-output-a.broker=mq-a",
    "runtime-lite.destinations.mq-output-b.transport=JMS",
    "runtime-lite.destinations.mq-output-b.broker=mq-b",
    "runtime-lite.destinations.kafka-output-a.transport=KAFKA",
    "runtime-lite.destinations.kafka-output-a.broker=kafka-a",
    "runtime-lite.destinations.kafka-output-b.transport=KAFKA",
    "runtime-lite.destinations.kafka-output-b.broker=kafka-b"
})
@Import(MultiBrokerIntegrationTest.Fixtures.class)
@EnabledIfEnvironmentVariable(named = "IIB_LITE_TEST_MULTI_BROKERS", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiBrokerIntegrationTest {

    private static final String SCHEMA = "lite_multi_" + UUID.randomUUID().toString().replace("-", "");
    private static final String TOPIC = "lite-multi-" + UUID.randomUUID();

    @Autowired
    private BrokerRegistry brokers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryTables tables;

    @Autowired
    private JmsMessageReader reader;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private JmsListenerEndpointRegistry listeners;

    private boolean topicACreated;
    private boolean topicBCreated;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        String input = required("IIB_LITE_TEST_MULTI_MQ_INPUT_QUEUE");
        String output = required("IIB_LITE_TEST_MULTI_MQ_OUTPUT_QUEUE");
        if (input.equals(output)) {
            throw new IllegalArgumentException("The test requires distinct dedicated input and output queues");
        }
        String mqA = required("IIB_LITE_TEST_MULTI_MQ_A_HOST") + ":" + required("IIB_LITE_TEST_MULTI_MQ_A_PORT");
        String mqB = required("IIB_LITE_TEST_MULTI_MQ_B_HOST") + ":" + required("IIB_LITE_TEST_MULTI_MQ_B_PORT");
        if (mqA.equals(mqB) || required("IIB_LITE_TEST_MULTI_KAFKA_A").equals(required("IIB_LITE_TEST_MULTI_KAFKA_B"))) {
            throw new IllegalArgumentException("The test requires separate broker endpoints");
        }
        registry.add("spring.datasource.url", () -> required("IIB_LITE_TEST_POSTGRES_URL"));
        registry.add("spring.datasource.username", () -> required("IIB_LITE_TEST_POSTGRES_USER"));
        registry.add("spring.datasource.password", () -> required("IIB_LITE_TEST_POSTGRES_PASSWORD"));
        registry.add("runtime-lite.outbox.schema", () -> SCHEMA);
        for (String suffix : List.of("a", "b")) {
            String prefix = "runtime-lite.transports.mq.mq-" + suffix + ".";
            String env = "IIB_LITE_TEST_MULTI_MQ_" + suffix.toUpperCase() + "_";
            registry.add(prefix + "host", () -> required(env + "HOST"));
            registry.add(prefix + "port", () -> required(env + "PORT"));
            registry.add(prefix + "queue-manager", () -> required(env + "MANAGER"));
            registry.add(prefix + "channel", () -> required(env + "CHANNEL"));
            registry.add(prefix + "username", () -> required(env + "USER"));
            registry.add(prefix + "password", () -> required(env + "PASSWORD"));
            registry.add("runtime-lite.transports.kafka.kafka-" + suffix + ".bootstrap-servers",
                () -> required("IIB_LITE_TEST_MULTI_KAFKA_" + suffix.toUpperCase()));
            registry.add("runtime-lite.routes.route-" + suffix + ".input-queue", () -> input);
            registry.add("runtime-lite.destinations.mq-output-" + suffix + ".name", () -> output);
            registry.add("runtime-lite.destinations.kafka-output-" + suffix + ".name", () -> TOPIC);
        }
    }

    @Test
    void routesEqualQueueAndTopicNamesToTheirConfiguredBrokers() throws Exception {
        String kafkaA = required("IIB_LITE_TEST_MULTI_KAFKA_A");
        String kafkaB = required("IIB_LITE_TEST_MULTI_KAFKA_B");
        createTopic(kafkaA);
        topicACreated = true;
        createTopic(kafkaB);
        topicBCreated = true;

        try (var consumerA = consumer(kafkaA);
             var consumerB = consumer(kafkaB);
             var contextA = brokers.requireJms("mq-a").createContext(JMSContext.AUTO_ACKNOWLEDGE);
             var contextB = brokers.requireJms("mq-b").createContext(JMSContext.AUTO_ACKNOWLEDGE);
             var outputA = contextA.createConsumer(contextA.createQueue(required("IIB_LITE_TEST_MULTI_MQ_OUTPUT_QUEUE")));
             var outputB = contextB.createConsumer(contextB.createQueue(required("IIB_LITE_TEST_MULTI_MQ_OUTPUT_QUEUE")))) {
            consumerA.subscribe(List.of(TOPIC));
            consumerB.subscribe(List.of(TOPIC));
            contextA.createProducer().send(contextA.createQueue(required("IIB_LITE_TEST_MULTI_MQ_INPUT_QUEUE")), "A");
            contextB.createProducer().send(contextB.createQueue(required("IIB_LITE_TEST_MULTI_MQ_INPUT_QUEUE")), "B");

            await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.receipts(), Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tables.outbox(), Integer.class)).isEqualTo(4);
            });
            assertThat(outputA.receiveNoWait()).isNull();
            assertThat(outputB.receiveNoWait()).isNull();
            assertThat(dispatcher.dispatchBatch()).isEqualTo(4);

            var deliveredA = outputA.receive(10000);
            var deliveredB = outputB.receive(10000);
            assertThat(deliveredA).isNotNull();
            assertThat(deliveredB).isNotNull();
            assertThat(new String(reader.read(deliveredA).body(), StandardCharsets.UTF_8)).isEqualTo("B:B");
            assertThat(new String(reader.read(deliveredB).body(), StandardCharsets.UTF_8)).isEqualTo("A:A");
            assertThat(outputA.receive(300)).isNull();
            assertThat(outputB.receive(300)).isNull();

            var messagesA = poll(consumerA);
            var messagesB = poll(consumerB);
            assertThat(messagesA).extracting(record -> new String(record.value(), StandardCharsets.UTF_8))
                .containsExactly("A:A");
            assertThat(messagesB).extracting(record -> new String(record.value(), StandardCharsets.UTF_8))
                .containsExactly("B:B");
        }
    }

    @AfterAll
    void cleanup() throws Exception {
        listeners.stop();
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        if (topicACreated) {
            deleteTopic(required("IIB_LITE_TEST_MULTI_KAFKA_A"));
        }
        if (topicBCreated) {
            deleteTopic(required("IIB_LITE_TEST_MULTI_KAFKA_B"));
        }
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be explicitly set for the disposable broker test");
        }
        return value;
    }

    private static void createTopic(String bootstrap) throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
        }
    }

    private static void deleteTopic(String bootstrap) throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
            admin.deleteTopics(List.of(TOPIC)).all().get(15, TimeUnit.SECONDS);
        }
    }

    private static KafkaConsumer<String, byte[]> consumer(String bootstrap) {
        return new KafkaConsumer<>(Map.<String, Object>of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG, "lite-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class));
    }

    private static List<ConsumerRecord<String, byte[]>> poll(KafkaConsumer<String, byte[]> consumer) {
        var records = new ArrayList<ConsumerRecord<String, byte[]>>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            return !records.isEmpty();
        });
        consumer.poll(Duration.ofSeconds(1)).forEach(records::add);
        return records;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        MessageProcessor processorA(MessagePublisher publisher) {
            return new NamedProcessor("A", "mq-output-b", publisher);
        }

        @Bean
        MessageProcessor processorB(MessagePublisher publisher) {
            return new NamedProcessor("B", "mq-output-a", publisher);
        }
    }

    @RequiredArgsConstructor
    static class NamedProcessor implements MessageProcessor {

        private final String name;
        private final String destination;
        private final MessagePublisher publisher;

        @Override
        public MessagePayload process(InboundMessage message) {
            var value = name + ":" + new String(message.body(), StandardCharsets.UTF_8);
            var payload = MessagePayload.of(value.getBytes(StandardCharsets.UTF_8));
            publisher.send(destination, payload);
            return payload;
        }
    }
}
