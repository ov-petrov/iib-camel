package ru.petrovov.iib_camel.runtime.delivery.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import ru.petrovov.iib_camel.runtime.delivery.config.DeliveryProperties;
import ru.petrovov.iib_camel.runtime.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime.transport.TransportSender;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxDispatcherTest {
    private final OutboxStore store = mock(OutboxStore.class);
    private final OutboxStore.Claim claim = new OutboxStore.Claim(new OutboundMessage(UUID.randomUUID(),
        "test", "synthetic-output", new byte[]{1}, List.of(), null), UUID.randomUUID(), 1);

    @Test
    void senderFailureIsRecorded() throws Exception {
        IOException unavailable = new IOException("synthetic broker unavailable");
        OutboxDispatcher dispatcher = dispatcher(message -> {
            throw unavailable;
        });

        assertEquals(0, dispatcher.dispatchOnce());
        verify(store).failed(claim, unavailable);
        verify(store, never()).processed(any());
    }

    @Test
    void persistenceFailureAfterConfirmationPropagatesWithoutRecordingSendFailure() {
        OutboxDispatcher dispatcher = dispatcher(message -> {
        });
        when(store.processed(claim)).thenThrow(new DataAccessResourceFailureException("synthetic commit failure"));

        assertThrows(DataAccessResourceFailureException.class, dispatcher::dispatchOnce);
        verify(store, never()).failed(any(), any());
    }

    @Test
    void errorsAreNotCaughtAsOrdinaryDeliveryFailures() {
        OutboxDispatcher dispatcher = dispatcher(message -> {
            throw new AssertionError("synthetic fatal error");
        });

        assertThrows(AssertionError.class, dispatcher::dispatchOnce);
        verify(store, never()).failed(any(), any());
        verify(store, never()).processed(any());
    }

    @Test
    void staleClaimDoesNotCountAsProcessed() {
        OutboxDispatcher dispatcher = dispatcher(message -> {
        });
        when(store.processed(claim)).thenReturn(false);

        assertEquals(0, dispatcher.dispatchOnce());
    }

    private OutboxDispatcher dispatcher(SenderAction action) {
        when(store.claim(Set.of("test"))).thenReturn(List.of(claim));
        TransportSender sender = new TransportSender() {
            @Override
            public String transport() {
                return "test";
            }

            @Override
            public void send(OutboundMessage message) throws Exception {
                action.send(message);
            }
        };
        return new OutboxDispatcher(store, new DeliveryProperties(), List.of(sender));
    }

    @FunctionalInterface
    private interface SenderAction {
        void send(OutboundMessage message) throws Exception;
    }
}
