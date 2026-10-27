package ru.petrovov.iib_camel.runtime_lite.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.petrovov.iib_camel.runtime_lite.jdbc.DeliveryTables;
import ru.petrovov.iib_camel.runtime_lite.message.MessagePayload;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OutboxStore {

    private static final TypeReference<Map<String, String>> HEADERS_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final DeliveryTables tables;

    public UUID append(Transport transport, String broker, String destination, MessagePayload payload) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + tables.outbox() + " " + """
                (id, transport, broker_id, destination, body, message_key, headers, status)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, id, transport.name(), broker, destination, payload.body(), payload.key(),
            json.writeValueAsString(payload.headers()), OutboxStatus.PENDING.name());
        return id;
    }

    Optional<OutboxMessage> lockNext() {
        return jdbc.query("SELECT * FROM " + tables.outbox() + " " + """
                WHERE status = ? AND available_at <= CURRENT_TIMESTAMP
                ORDER BY created_at, id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, this::read, OutboxStatus.PENDING.name()).stream().findFirst();
    }

    void markSent(OutboxMessage message) {
        jdbc.update("UPDATE " + tables.outbox() + " " + """
                SET status = ?, attempts = attempts + 1, sent_at = CURRENT_TIMESTAMP, last_error = NULL
                WHERE id = ?
                """, OutboxStatus.SENT.name(), message.id());
    }

    void markFailed(OutboxMessage message, int maxAttempts, Duration delay, Exception failure) {
        OutboxStatus status = message.attempts() + 1 >= maxAttempts ? OutboxStatus.FAILED : OutboxStatus.PENDING;
        jdbc.update("UPDATE " + tables.outbox() + " " + """
                SET status = ?, attempts = attempts + 1,
                    available_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), last_error = ?
                WHERE id = ?
                """, status.name(), delay.toMillis(), failure.getClass().getSimpleName(), message.id());
    }

    private OutboxMessage read(ResultSet row, int index) throws SQLException {
        var payload = new MessagePayload(row.getBytes("body"), row.getString("message_key"),
            json.readValue(row.getString("headers"), HEADERS_TYPE));
        return new OutboxMessage(row.getObject("id", UUID.class), Transport.valueOf(row.getString("transport")),
            row.getString("broker_id"), row.getString("destination"), payload, row.getInt("attempts"));
    }
}
