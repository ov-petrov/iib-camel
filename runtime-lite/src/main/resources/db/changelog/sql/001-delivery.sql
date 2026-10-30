CREATE TABLE ${deliverySchema}.inbound_receipt (
    route_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (route_id, message_id)
);

CREATE TABLE ${deliverySchema}.outbox_message (
    id UUID PRIMARY KEY,
    transport TEXT NOT NULL CHECK (transport IN ('JMS', 'KAFKA')),
    destination TEXT NOT NULL,
    body TEXT NOT NULL,
    message_key TEXT,
    headers JSONB NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    last_error TEXT
);

CREATE INDEX outbox_message_dispatch_idx
    ON ${deliverySchema}.outbox_message (status, available_at, created_at, id);
