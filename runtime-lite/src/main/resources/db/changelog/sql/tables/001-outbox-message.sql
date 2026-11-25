CREATE TABLE IF NOT EXISTS ${deliverySchema}.outbox_message (
    id UUID PRIMARY KEY,
    transport TEXT NOT NULL CHECK (transport IN ('JMS', 'KAFKA')),
    destination TEXT NOT NULL,
    body TEXT,
    body_xml XML,
    message_key TEXT,
    headers JSONB NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    sent_at TIMESTAMPTZ,
    last_error TEXT,
    jms_reply_to TEXT,
    CHECK ((body IS NULL) <> (body_xml IS NULL))
);

CREATE INDEX IF NOT EXISTS outbox_message_dispatch_idx
    ON ${deliverySchema}.outbox_message (status, available_at, created_at, id);
