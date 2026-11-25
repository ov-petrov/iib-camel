package ru.petrovov.iib_camel.runtime_lite.enrichment;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.petrovov.iib_camel.runtime_lite.jdbc.BodyColumns;
import ru.petrovov.iib_camel.runtime_lite.jdbc.DeliveryTables;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.MessageBody;
import ru.petrovov.iib_camel.runtime_lite.processor.EnrichmentFailure;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsPayloadFormat;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class EnrichmentStore {

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() { };
    private static final String STATE_COLUMNS = "id, route_id, operation_key, reply_queue, reply_payload_format, "
        + "status, failure_code, created_at, sent_at, dispatch_deadline, reply_deadline";

    private final JdbcTemplate jdbc;
    private final DeliveryTables tables;
    private final JsonMapper json;

    void start(String routeId, String messageId, String operationKey,
               String correlationId, String replyQueue, JmsPayloadFormat replyPayloadFormat, UUID requestId,
               Duration dispatchTimeout, Duration replyTimeout) {
        String sql = """
            INSERT INTO %s (id, route_id, message_id, operation_key, correlation_id, reply_queue, reply_payload_format,
                request_outbox_id, created_at, dispatch_deadline, reply_timeout_ms, status)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, started_at,
                started_at + (? * INTERVAL '1 millisecond'), ?, ?
            FROM (SELECT clock_timestamp() AS started_at) AS timing
            """.formatted(tables.enrichment());
        jdbc.update(sql, UUID.randomUUID(), routeId, messageId, operationKey, correlationId, replyQueue,
            replyPayloadFormat.name(), requestId, dispatchTimeout.toMillis(), replyTimeout.toMillis(),
            EnrichmentStatus.PENDING_SEND.name());
    }

    State lockRequest(UUID requestId) {
        String sql = "SELECT " + STATE_COLUMNS + " FROM " + tables.enrichment()
            + " WHERE request_outbox_id = ? FOR UPDATE";
        return jdbc.queryForObject(sql, this::readState, requestId);
    }

    Optional<State> lockReply(String correlationId) {
        String sql = "SELECT " + STATE_COLUMNS + " FROM " + tables.enrichment()
            + " WHERE correlation_id = ? FOR UPDATE";
        return jdbc.query(sql, this::readState, correlationId).stream().findFirst();
    }

    Optional<State> lockExpired(EnrichmentStatus status) {
        String deadline = status == EnrichmentStatus.PENDING_SEND ? "dispatch_deadline" : "reply_deadline";
        String sql = """
            SELECT %s FROM %s WHERE status = ? AND %s <= (SELECT clock_timestamp())
            ORDER BY %s, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """.formatted(STATE_COLUMNS, tables.enrichment(), deadline, deadline);
        return jdbc.query(sql, this::readState, status.name()).stream().findFirst();
    }

    Optional<Continuation> lockContinuation() {
        String sql = """
            SELECT %s, inbound.processor_id, inbound.payload_format, message_id,
                COALESCE(inbound.body_xml::text, inbound.body) AS input_body,
                inbound.headers AS input_headers,
                reply_message_id, COALESCE(reply_xml::text, reply_body) AS reply_body, reply_headers
            FROM %s AS workflow JOIN %s AS inbound USING (route_id, message_id)
            WHERE status = ? ORDER BY ready_at, id LIMIT 1 FOR UPDATE OF workflow SKIP LOCKED
            """.formatted(STATE_COLUMNS, tables.enrichment(), tables.inboundMessages());
        return jdbc.query(sql, this::readContinuation, EnrichmentStatus.READY_TO_MAP.name()).stream().findFirst();
    }

    OffsetDateTime now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }

    long activeCount() {
        return jdbc.queryForObject("SELECT count(*) FROM " + tables.enrichment() + " WHERE status IN (?, ?, ?)",
            Long.class, EnrichmentStatus.PENDING_SEND.name(), EnrichmentStatus.WAITING_REPLY.name(),
            EnrichmentStatus.READY_TO_MAP.name());
    }

    double oldestActiveSeconds() {
        String sql = """
            SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp() - min(created_at)), 0)
            FROM %s WHERE status IN (?, ?, ?)
            """.formatted(tables.enrichment());
        return jdbc.queryForObject(sql, Double.class, EnrichmentStatus.PENDING_SEND.name(),
            EnrichmentStatus.WAITING_REPLY.name(), EnrichmentStatus.READY_TO_MAP.name());
    }

    List<Contract> activeContracts() {
        String sql = """
            SELECT DISTINCT inbound.processor_id, operation_key, reply_queue
            FROM %s AS workflow JOIN %s AS inbound USING (route_id, message_id)
            WHERE status IN (?, ?, ?)
            """.formatted(tables.enrichment(), tables.inboundMessages());
        return jdbc.query(sql, (row, index) -> new Contract(row.getString("processor_id"),
                row.getString("operation_key"), row.getString("reply_queue")), EnrichmentStatus.PENDING_SEND.name(),
            EnrichmentStatus.WAITING_REPLY.name(), EnrichmentStatus.READY_TO_MAP.name());
    }

    void sent(State state, OffsetDateTime sentAt) {
        String sql = """
            UPDATE %s SET status = ?, sent_at = ?,
                reply_deadline = CAST(? AS timestamptz) + (reply_timeout_ms * INTERVAL '1 millisecond')
            WHERE id = ?
            """.formatted(tables.enrichment());
        jdbc.update(sql, EnrichmentStatus.WAITING_REPLY.name(), sentAt, sentAt, state.id());
    }

    void received(State state, InboundMessage reply, MessageBody replyBody, OffsetDateTime receivedAt) {
        String sql = """
            UPDATE %s SET status = ?, ready_at = ?, reply_received_at = ?,
                reply_message_id = ?, reply_body = ?, reply_xml = XMLPARSE(DOCUMENT ?),
                reply_headers = CAST(? AS jsonb) WHERE id = ?
            """.formatted(tables.enrichment());
        var body = BodyColumns.of(replyBody);
        jdbc.update(sql, EnrichmentStatus.READY_TO_MAP.name(), receivedAt, receivedAt, reply.id(), body.text(), body.xml(),
            json.writeValueAsString(reply.headers()), state.id());
    }

    void readyWithFailure(State state, EnrichmentFailure failure) {
        String sql = """
            UPDATE %s SET status = ?, ready_at = clock_timestamp(), failure_code = ? WHERE id = ?
            """.formatted(tables.enrichment());
        jdbc.update(sql, EnrichmentStatus.READY_TO_MAP.name(), failure.name(), state.id());
    }

    void finish(State state, EnrichmentFailure failure, UUID resultId) {
        var status = failure == null ? EnrichmentStatus.COMPLETED : EnrichmentStatus.FAILED;
        String sql = """
            UPDATE %s SET status = ?, failure_code = ?, result_outbox_id = ?,
                finished_at = clock_timestamp(),
                reply_body = NULL, reply_xml = NULL, reply_headers = NULL WHERE id = ?
            """.formatted(tables.enrichment());
        jdbc.update(sql, status.name(), failure == null ? null : failure.name(), resultId, state.id());
    }

    private State readState(ResultSet row, int index) throws SQLException {
        String failure = row.getString("failure_code");
        return State.builder()
            .id(row.getObject("id", UUID.class))
            .routeId(row.getString("route_id"))
            .operationKey(row.getString("operation_key"))
            .replyQueue(row.getString("reply_queue"))
            .replyPayloadFormat(JmsPayloadFormat.valueOf(row.getString("reply_payload_format")))
            .status(EnrichmentStatus.valueOf(row.getString("status")))
            .failure(failure == null ? null : EnrichmentFailure.valueOf(failure))
            .createdAt(row.getObject("created_at", OffsetDateTime.class))
            .sentAt(row.getObject("sent_at", OffsetDateTime.class))
            .dispatchDeadline(row.getObject("dispatch_deadline", OffsetDateTime.class))
            .replyDeadline(row.getObject("reply_deadline", OffsetDateTime.class))
            .build();
    }

    private Continuation readContinuation(ResultSet row, int index) throws SQLException {
        var state = readState(row, index);
        var input = new InboundMessage(row.getString("message_id"), row.getString("input_body"),
            json.readValue(row.getString("input_headers"), HEADERS));
        var reply = state.failure() == null
            ? new InboundMessage(row.getString("reply_message_id"), row.getString("reply_body"),
                json.readValue(row.getString("reply_headers"), HEADERS))
            : null;
        return new Continuation(state, row.getString("processor_id"),
            JmsPayloadFormat.valueOf(row.getString("payload_format")), input, reply);
    }

    @Builder
    record State(UUID id, String routeId, String operationKey, String replyQueue, JmsPayloadFormat replyPayloadFormat,
                 EnrichmentStatus status,
                 EnrichmentFailure failure, OffsetDateTime createdAt, OffsetDateTime sentAt,
                 OffsetDateTime dispatchDeadline, OffsetDateTime replyDeadline) {

        EnrichmentFailure timeout(OffsetDateTime now) {
            return switch (status) {
                case PENDING_SEND -> now.isBefore(dispatchDeadline) ? null : EnrichmentFailure.DISPATCH_TIMEOUT;
                case WAITING_REPLY -> now.isBefore(replyDeadline) ? null : EnrichmentFailure.REPLY_TIMEOUT;
                default -> null;
            };
        }
    }

    record Continuation(State state, String processorId, JmsPayloadFormat inputFormat,
                        InboundMessage input, InboundMessage reply) {
    }

    record Contract(String processorId, String operationKey, String replyQueue) {
    }
}
