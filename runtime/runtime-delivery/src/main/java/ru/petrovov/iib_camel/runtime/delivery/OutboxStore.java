package ru.petrovov.iib_camel.runtime.delivery;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.petrovov.iib_camel.runtime.message.OutboundMessage;
import tools.jackson.databind.ObjectMapper;

final class OutboxStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final HeaderCodec headers;
    private final DeliveryProperties properties;

    OutboxStore(DataSource dataSource, DeliveryProperties properties, ObjectMapper mapper) {
        PostgresDatabase.require(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.headers = new HeaderCodec(mapper);
        this.properties = properties;
    }

    void requireSupportedTransports(Set<String> supported) {
        List<String> pending = jdbc.queryForList("""
                SELECT DISTINCT transport FROM runtime_outbox WHERE status IN ('READY', 'IN_PROGRESS')
                """, String.class);
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
                        SELECT id FROM runtime_outbox WHERE attempt_count >= ? AND (
                            status = 'READY' OR (status = 'IN_PROGRESS' AND lease_until <= clock_timestamp()))
                        ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT ?
                    )
                    UPDATE runtime_outbox AS event SET status = 'FAILED', claim_token = NULL, lease_until = NULL,
                        last_error = 'Delivery attempts exhausted'
                    FROM exhausted WHERE event.id = exhausted.id
                    """, properties.getMaxAttempts(), properties.getBatchSize());
            String placeholders = String.join(", ", Collections.nCopies(supported.size(), "?"));
            String sql = """
                    WITH candidates AS (
                        SELECT id FROM runtime_outbox
                        WHERE attempt_count < ? AND transport IN (%s) AND (
                            (status = 'READY' AND available_at <= clock_timestamp()) OR
                            (status = 'IN_PROGRESS' AND lease_until <= clock_timestamp()))
                        ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT ?
                    )
                    UPDATE runtime_outbox AS event
                    SET status = 'IN_PROGRESS', claim_token = ?,
                        lease_until = clock_timestamp() + (? * INTERVAL '1 millisecond'),
                        attempt_count = event.attempt_count + 1
                    FROM candidates WHERE event.id = candidates.id
                    RETURNING event.id, event.transport, event.destination, event.payload,
                        event.headers, event.partition_key, event.attempt_count
                    """.formatted(placeholders);
            List<Object> arguments = new ArrayList<>();
            arguments.add(properties.getMaxAttempts());
            arguments.addAll(supported);
            arguments.add(properties.getBatchSize());
            arguments.add(token);
            arguments.add(properties.getLease().toMillis());
            return jdbc.query(sql, (result, row) -> new Claim(new OutboundMessage(
                            result.getObject("id", UUID.class), result.getString("transport"),
                            result.getString("destination"), result.getBytes("payload"),
                            headers.decode(result.getString("headers")), result.getString("partition_key")),
                            token, result.getInt("attempt_count")),
                    arguments.toArray());
        });
    }

    boolean processed(Claim claim) {
        return transaction.execute(status -> jdbc.update("""
                UPDATE runtime_outbox SET status = 'PROCESSED', processed_at = clock_timestamp(),
                    claim_token = NULL, lease_until = NULL, last_error = NULL
                WHERE id = ? AND claim_token = ? AND status = 'IN_PROGRESS'
                """, claim.message().id(), claim.token()) == 1);
    }

    boolean failed(Claim claim, Exception failure) {
        boolean terminal = claim.attempt() >= properties.getMaxAttempts();
        String error = failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
        String boundedError = error.substring(0, Math.min(error.length(), 2000));
        return transaction.execute(status -> jdbc.update("""
                UPDATE runtime_outbox SET status = ?, claim_token = NULL, lease_until = NULL,
                    available_at = clock_timestamp() + (? * INTERVAL '1 millisecond'), last_error = ?
                WHERE id = ? AND claim_token = ? AND status = 'IN_PROGRESS'
                """, terminal ? "FAILED" : "READY", properties.backoffMillis(claim.attempt()),
                boundedError, claim.message().id(), claim.token()) == 1);
    }

    record Claim(OutboundMessage message, UUID token, int attempt) {}
}
