package ru.petrovov.iib_camel.runtime_lite.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.petrovov.iib_camel.runtime_lite.jdbc.BodyColumns;
import ru.petrovov.iib_camel.runtime_lite.jdbc.DeliveryTables;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
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

    public UUID append(OutboundMessage message) {
        return append(message, null);
    }

    public UUID append(OutboundMessage message, String replyQueue) {
        UUID id = UUID.randomUUID();
        String sql = """
                INSERT INTO %s
                (id, transport, destination, body, body_xml, message_key, headers, status, jms_reply_to)
                VALUES (?, ?, ?, ?, XMLPARSE(DOCUMENT ?), ?, CAST(? AS jsonb), ?, ?)
                """.formatted(tables.outbox());

        var body = BodyColumns.of(message.payload());
        jdbc.update(sql, id, message.transport().name(), message.destination(), body.text(), body.xml(), message.key(),
            json.writeValueAsString(message.headers()), OutboxStatus.PENDING.name(), replyQueue);
        return id;
    }

    Optional<OutboxMessage> lockNext() {
        String sql = """
                SELECT * FROM %s
                WHERE status = ? AND available_at <= (SELECT clock_timestamp())
                ORDER BY created_at, id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """.formatted(tables.outbox());

        return jdbc.query(sql, this::read, OutboxStatus.PENDING.name()).stream().findFirst();
    }

    OffsetDateTime markSent(OutboxMessage message) {
        String sql = """
                UPDATE %s
                SET status = ?, attempts = attempts + 1, sent_at = clock_timestamp(), last_error = NULL
                WHERE id = ?
                RETURNING sent_at
                """.formatted(tables.outbox());

        return jdbc.queryForObject(sql, OffsetDateTime.class, OutboxStatus.SENT.name(), message.id());
    }

    void markFailed(OutboxMessage message, int maxAttempts, Duration delay, Exception failure) {
        OutboxStatus status = message.attempts() + 1 >= maxAttempts ? OutboxStatus.FAILED : OutboxStatus.PENDING;
        String sql = """
                UPDATE %s
                SET status = ?, attempts = attempts + 1,
                    available_at = clock_timestamp() + (? * INTERVAL '1 millisecond'), last_error = ?
                WHERE id = ?
                """.formatted(tables.outbox());

        jdbc.update(sql, status.name(), delay.toMillis(), failure.getClass().getSimpleName(), message.id());
    }

    void markCancelled(OutboxMessage message, String reason, boolean attempted) {
        String sql = """
                UPDATE %s
                SET status = ?, last_error = ?, attempts = attempts + ?
                WHERE id = ?
                """.formatted(tables.outbox());

        jdbc.update(sql, OutboxStatus.CANCELLED.name(), reason, attempted ? 1 : 0, message.id());
    }

    private OutboxMessage read(ResultSet row, int index) throws SQLException {
        var body = new BodyColumns(row.getString("body"), row.getString("body_xml")).toMessageBody();
        var message = OutboundMessage.builder()
            .transport(Transport.valueOf(row.getString("transport")))
            .destination(row.getString("destination"))
            .payload(body)
            .key(row.getString("message_key"))
            .headers(json.readValue(row.getString("headers"), HEADERS_TYPE))
            .build();
        return OutboxMessage.builder()
            .id(row.getObject("id", UUID.class))
            .message(message)
            .attempts(row.getInt("attempts"))
            .replyQueue(row.getString("jms_reply_to"))
            .build();
    }
}
