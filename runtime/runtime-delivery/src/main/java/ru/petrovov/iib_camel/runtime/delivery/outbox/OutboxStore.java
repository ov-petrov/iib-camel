package ru.petrovov.iib_camel.runtime.delivery.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.petrovov.iib_camel.runtime.delivery.config.DeliveryProperties;
import ru.petrovov.iib_camel.runtime.delivery.jdbc.DeliveryTables;
import ru.petrovov.iib_camel.runtime.delivery.jdbc.HeaderCodec;
import ru.petrovov.iib_camel.runtime.message.OutboundMessage;

import java.util.*;

@RequiredArgsConstructor
public final class OutboxStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final HeaderCodec headers;
    private final DeliveryProperties properties;
    private final DeliveryTables tables;

    void requireSupportedTransports(Set<String> supported) {
        List<String> pending = jdbc.queryForList("""
                SELECT DISTINCT transport FROM %s WHERE status IN (?, ?)
                """.formatted(tables.outbox()), String.class,
            OutboxStatus.READY.getDatabaseValue(), OutboxStatus.IN_PROGRESS.getDatabaseValue());
        for (String transport : pending) {
            if (!supported.contains(transport)) {
                throw new IllegalStateException("No TransportSender configured for pending transport: " + transport);
            }
        }
    }

    List<Claim> claim(Set<String> supported) {
        if (supported.isEmpty()) {
            return List.of();
        }
        UUID token = UUID.randomUUID();
        return transaction.execute(status -> {
            jdbc.update("""
                    WITH exhausted AS (
                        SELECT id FROM %s WHERE attempt_count >= ? AND (
                            status = ? OR (status = ? AND lease_until <= clock_timestamp()))
                        ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT ?
                    )
                    UPDATE %s AS event SET status = ?, claim_token = NULL, lease_until = NULL,
                        last_error = 'Delivery attempts exhausted'
                    FROM exhausted WHERE event.id = exhausted.id
                    """.formatted(tables.outbox(), tables.outbox()),
                properties.getMaxAttempts(), OutboxStatus.READY.getDatabaseValue(),
                OutboxStatus.IN_PROGRESS.getDatabaseValue(), properties.getBatchSize(),
                OutboxStatus.FAILED.getDatabaseValue());

            String placeholders = String.join(", ", Collections.nCopies(supported.size(), "?"));
            String sql = """
                WITH candidates AS (
                    SELECT id FROM %s
                    WHERE attempt_count < ? AND transport IN (%s) AND (
                        (status = ? AND available_at <= clock_timestamp()) OR
                        (status = ? AND lease_until <= clock_timestamp()))
                    ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT ?
                )
                UPDATE %s AS event
                SET status = ?, claim_token = ?,
                    lease_until = clock_timestamp() + (? * INTERVAL '1 millisecond'),
                    attempt_count = event.attempt_count + 1
                FROM candidates WHERE event.id = candidates.id
                RETURNING event.id, event.transport, event.destination, event.payload,
                    event.headers, event.partition_key, event.attempt_count
                """.formatted(tables.outbox(), placeholders, tables.outbox());
            List<Object> arguments = new ArrayList<>();
            arguments.add(properties.getMaxAttempts());
            arguments.addAll(supported);
            arguments.add(OutboxStatus.READY.getDatabaseValue());
            arguments.add(OutboxStatus.IN_PROGRESS.getDatabaseValue());
            arguments.add(properties.getBatchSize());
            arguments.add(OutboxStatus.IN_PROGRESS.getDatabaseValue());
            arguments.add(token);
            arguments.add(properties.getLease().toMillis());

            return jdbc.query(sql, (result, row) -> new Claim(
                    new OutboundMessage(
                        result.getObject("id", UUID.class), result.getString("transport"),
                        result.getString("destination"), result.getBytes("payload"),
                        headers.decode(result.getString("headers")), result.getString("partition_key")),
                    token, result.getInt("attempt_count")),
                arguments.toArray());
        });
    }

    boolean processed(Claim claim) {
        return transaction.execute(status -> jdbc.update("""
                UPDATE %s SET status = ?, processed_at = clock_timestamp(),
                    claim_token = NULL, lease_until = NULL, last_error = NULL
                WHERE id = ? AND claim_token = ? AND status = ?
                """.formatted(tables.outbox()), OutboxStatus.PROCESSED.getDatabaseValue(), claim.message().id(), claim.token(),
            OutboxStatus.IN_PROGRESS.getDatabaseValue()) == 1);
    }

    boolean failed(Claim claim, Exception failure) {
        boolean terminal = claim.attempt() >= properties.getMaxAttempts();
        String error = failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
        String boundedError = error.substring(0, Math.min(error.length(), 2000));
        return transaction.execute(status -> jdbc.update("""
                UPDATE %s SET status = ?, claim_token = NULL, lease_until = NULL,
                    available_at = clock_timestamp() + (? * INTERVAL '1 millisecond'), last_error = ?
                WHERE id = ? AND claim_token = ? AND status = ?
                """.formatted(tables.outbox()),
            terminal ? OutboxStatus.FAILED.getDatabaseValue() : OutboxStatus.READY.getDatabaseValue(),
            properties.backoffMillis(claim.attempt()), boundedError, claim.message().id(), claim.token(),
            OutboxStatus.IN_PROGRESS.getDatabaseValue()) == 1);
    }

    record Claim(OutboundMessage message, UUID token, int attempt) {
    }
}
