package ru.petrovov.iib_camel.runtime.delivery.outbox;

import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.petrovov.iib_camel.runtime.delivery.config.DeliveryProperties;
import ru.petrovov.iib_camel.runtime.transport.TransportSender;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OutboxDispatcher {
    private final OutboxStore store;
    private final Map<String, TransportSender> senders;

    public OutboxDispatcher(OutboxStore store, DeliveryProperties properties, List<TransportSender> senders) {
        properties.validate();
        this.store = store;
        Map<String, TransportSender> registered = new HashMap<>();
        for (TransportSender sender : senders) {
            String transport = Objects.requireNonNull(sender.transport(), "sender transport");
            if (transport.isBlank() || registered.putIfAbsent(transport, sender) != null) {
                throw new IllegalArgumentException("TransportSender names must be nonblank and unique");
            }
        }
        this.senders = Map.copyOf(registered);
        store.requireSupportedTransports(this.senders.keySet());
    }

    public int dispatchOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox dispatch must be called outside a transaction");
        }
        store.requireSupportedTransports(senders.keySet());
        List<OutboxStore.Claim> claims = store.claim(senders.keySet());
        int processed = 0;
        for (OutboxStore.Claim claim : claims) {
            try {
                senders.get(claim.message().transport()).send(claim.message());
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                store.failed(claim, failure);
                continue;
            }
            // A database failure after confirmation leaves ownership intact for a fenced retry.
            if (store.processed(claim)) {
                processed++;
            }
        }
        return processed;
    }
}
