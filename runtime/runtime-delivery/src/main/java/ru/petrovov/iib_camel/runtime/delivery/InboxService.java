package ru.petrovov.iib_camel.runtime.delivery;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.petrovov.iib_camel.runtime.message.InboundMessage;
import ru.petrovov.iib_camel.runtime.message.OutboundMessage;
import tools.jackson.databind.ObjectMapper;

public final class InboxService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final HeaderCodec headers;

    public InboxService(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public InboxService(DataSource dataSource, ObjectMapper mapper) {
        PostgresDatabase.require(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.headers = new HeaderCodec(mapper);
    }

    /** Return permits source acknowledgement; handler JDBC writes must use this service's DataSource. */
    public Acceptance accept(InboundMessage message, MessageHandler handler) throws Exception {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(handler, "handler");
        byte[] payloadHash = hash(message.payload());
        try {
            return transaction.execute(status -> {
                int inserted = jdbc.update("""
                        INSERT INTO runtime_inbox(module_id, source, message_id, payload_hash)
                        VALUES (?, ?, ?, ?) ON CONFLICT (module_id, source, message_id) DO NOTHING
                        """, message.moduleId(), message.source(), message.messageId(), payloadHash);
                if (inserted == 0) {
                    byte[] acceptedHash = jdbc.queryForObject("""
                            SELECT payload_hash FROM runtime_inbox
                            WHERE module_id = ? AND source = ? AND message_id = ?
                            """, byte[].class, message.moduleId(), message.source(), message.messageId());
                    if (!MessageDigest.isEqual(payloadHash, acceptedHash)) {
                        throw new ConflictingMessageException();
                    }
                    return Acceptance.DUPLICATE;
                }
                List<OutboundMessage> outgoing;
                try {
                    outgoing = List.copyOf(handler.handle(message));
                } catch (Exception exception) {
                    throw new HandlerFailure(exception);
                }
                for (OutboundMessage event : outgoing) {
                    jdbc.update("""
                            INSERT INTO runtime_outbox(id, module_id, source, message_id, transport,
                                destination, payload, headers, partition_key)
                            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                            """, event.id(), message.moduleId(), message.source(), message.messageId(),
                            event.transport(), event.destination(), event.payload(),
                            headers.encode(event.headers()), event.key());
                }
                return Acceptance.ACCEPTED;
            });
        } catch (HandlerFailure failure) {
            throw failure.original;
        }
    }

    private static byte[] hash(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class HandlerFailure extends RuntimeException {
        private final Exception original;

        private HandlerFailure(Exception original) {
            super(original);
            this.original = original;
        }
    }
}
