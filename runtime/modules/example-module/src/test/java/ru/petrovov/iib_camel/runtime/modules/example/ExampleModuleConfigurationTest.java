package ru.petrovov.iib_camel.runtime.modules.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.kafka.consumer.KafkaManualCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import ru.petrovov.iib_camel.runtime.delivery.Acceptance;
import ru.petrovov.iib_camel.runtime.delivery.InboxService;
import ru.petrovov.iib_camel.runtime.delivery.MessageHandler;
import ru.petrovov.iib_camel.runtime.message.InboundMessage;

class ExampleModuleConfigurationTest {
    private final InboxService inbox = mock(InboxService.class);
    private final ExampleModuleConfiguration configuration = new ExampleModuleConfiguration();
    private org.apache.camel.Processor processor;

    @BeforeEach
    void setUp() {
        processor = configuration.exampleReceive(inbox, "synthetic-source", "jms", "synthetic-output");
    }

    @Test
    void sendsByteBodyAndApplicationIdentityToInbox() throws Exception {
        byte[] payload = {0, 1, -1};
        var exchange = exchange(payload);
        exchange.getMessage().setHeader("applicationEventId", "event-17");
        when(inbox.accept(any(InboundMessage.class), any(MessageHandler.class))).thenReturn(Acceptance.ACCEPTED);

        processor.process(exchange);

        var captured = ArgumentCaptor.forClass(InboundMessage.class);
        verify(inbox).accept(captured.capture(), any(MessageHandler.class));
        assertThat(captured.getValue().moduleId()).isEqualTo("example");
        assertThat(captured.getValue().source()).isEqualTo("synthetic-source");
        assertThat(captured.getValue().messageId()).isEqualTo("event-17");
        assertThat(captured.getValue().payload()).containsExactly(payload);
    }

    @Test
    void commitsKafkaOnlyAfterInboxAcceptanceReturns() throws Exception {
        var exchange = kafkaExchange(new byte[]{4}, "events-a", 2, 11L);
        KafkaManualCommit commit = mock(KafkaManualCommit.class);
        exchange.getMessage().setHeader(KafkaConstants.MANUAL_COMMIT, commit);
        when(inbox.accept(any(InboundMessage.class), any(MessageHandler.class))).thenReturn(Acceptance.ACCEPTED);

        processor.process(exchange);

        InOrder order = inOrder(inbox, commit);
        order.verify(inbox).accept(any(InboundMessage.class), any(MessageHandler.class));
        order.verify(commit).commit();
    }

    @Test
    void doesNotCommitKafkaWhenInboxAcceptanceThrows() throws Exception {
        var exchange = kafkaExchange(new byte[]{4}, "events-a", 2, 11L);
        KafkaManualCommit commit = mock(KafkaManualCommit.class);
        exchange.getMessage().setHeader(KafkaConstants.MANUAL_COMMIT, commit);
        when(inbox.accept(any(InboundMessage.class), any(MessageHandler.class)))
                .thenThrow(new IllegalStateException("storage unavailable"));

        assertThatThrownBy(() -> processor.process(exchange))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("storage unavailable");

        verify(commit, never()).commit();
    }

    @Test
    void commitsKafkaDuplicateAfterAcceptanceReturns() throws Exception {
        var exchange = kafkaExchange(new byte[]{4}, "events-a", 2, 11L);
        KafkaManualCommit commit = mock(KafkaManualCommit.class);
        exchange.getMessage().setHeader(KafkaConstants.MANUAL_COMMIT, commit);
        when(inbox.accept(any(InboundMessage.class), any(MessageHandler.class))).thenReturn(Acceptance.DUPLICATE);

        processor.process(exchange);

        verify(commit).commit();
    }

    @Test
    void scopesKafkaIdentityByTopicAndPartition() throws Exception {
        when(inbox.accept(any(InboundMessage.class), any(MessageHandler.class))).thenReturn(Acceptance.ACCEPTED);
        var first = kafkaExchange(new byte[]{1}, "events-a", 2, 11L);
        var second = kafkaExchange(new byte[]{1}, "events-b", 7, 11L);
        first.getMessage().setHeader(KafkaConstants.MANUAL_COMMIT, mock(KafkaManualCommit.class));
        second.getMessage().setHeader(KafkaConstants.MANUAL_COMMIT, mock(KafkaManualCommit.class));

        processor.process(first);
        processor.process(second);

        var captured = ArgumentCaptor.forClass(InboundMessage.class);
        verify(inbox, org.mockito.Mockito.times(2)).accept(captured.capture(), any(MessageHandler.class));
        assertThat(captured.getAllValues()).extracting(InboundMessage::source)
                .containsExactly("synthetic-source:events-a:2", "synthetic-source:events-b:7");
        assertThat(captured.getAllValues()).extracting(InboundMessage::messageId).containsExactly("11", "11");
    }

    @Test
    void rejectsMissingStableIdentity() {
        assertThatThrownBy(() -> processor.process(exchange("payload")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Stable input identity is required");

        verifyNoInteractions(inbox);
    }

    @Test
    void rejectsKafkaWithoutManualCommit() {
        var exchange = kafkaExchange("payload", "events-a", 2, 11L);
        exchange.getMessage().setHeader("applicationEventId", "event-17");

        assertThatThrownBy(() -> processor.process(exchange))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Kafka requires manual commit after PostgreSQL commit");

        verifyNoInteractions(inbox);
    }

    @Test
    void rejectsUnsupportedBody() {
        var exchange = exchange(42);
        exchange.getMessage().setHeader("applicationEventId", "event-17");

        assertThatThrownBy(() -> processor.process(exchange))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Example accepts bytes or UTF-8 text only");

        verifyNoInteractions(inbox);
    }

    @Test
    void encodesTextBodyAsUtf8() throws Exception {
        when(inbox.accept(any(InboundMessage.class), any(MessageHandler.class))).thenReturn(Acceptance.ACCEPTED);
        var exchange = exchange("café");
        exchange.getMessage().setHeader("applicationEventId", "event-18");

        processor.process(exchange);

        var captured = ArgumentCaptor.forClass(InboundMessage.class);
        verify(inbox).accept(captured.capture(), any(MessageHandler.class));
        assertThat(captured.getValue().payload()).containsExactly("café".getBytes(StandardCharsets.UTF_8));
    }

    private static DefaultExchange exchange(Object body) {
        var exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getMessage().setBody(body);
        return exchange;
    }

    private static DefaultExchange kafkaExchange(Object body, String topic, int partition, long offset) {
        var exchange = exchange(body);
        exchange.getMessage().setHeader(KafkaConstants.TOPIC, topic);
        exchange.getMessage().setHeader(KafkaConstants.PARTITION, partition);
        exchange.getMessage().setHeader(KafkaConstants.OFFSET, offset);
        return exchange;
    }
}
