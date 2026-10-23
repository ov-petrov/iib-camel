CREATE TABLE "${deliverySchema}".runtime_inbox
(
    module_id    TEXT        NOT NULL,
    source       TEXT        NOT NULL,
    message_id   TEXT        NOT NULL,
    payload_hash BYTEA       NOT NULL,
    accepted_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (module_id, source, message_id)
);

CREATE TABLE "${deliverySchema}".runtime_outbox
(
    id            UUID PRIMARY KEY,
    module_id     TEXT        NOT NULL,
    source        TEXT        NOT NULL,
    message_id    TEXT        NOT NULL,
    transport     TEXT        NOT NULL,
    destination   TEXT        NOT NULL,
    payload       BYTEA       NOT NULL,
    headers       JSONB       NOT NULL,
    partition_key TEXT,
    status        TEXT        NOT NULL DEFAULT 'READY' CHECK (status IN ('READY', 'IN_PROGRESS', 'PROCESSED', 'FAILED')),
    attempt_count INTEGER     NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    available_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token   UUID,
    lease_until   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at  TIMESTAMPTZ,
    last_error    TEXT,
    FOREIGN KEY (module_id, source, message_id)
        REFERENCES "${deliverySchema}".runtime_inbox (module_id, source, message_id),
    CHECK ((status = 'IN_PROGRESS' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'IN_PROGRESS' AND claim_token IS NULL AND lease_until IS NULL))
);

CREATE INDEX runtime_outbox_ready ON "${deliverySchema}".runtime_outbox (available_at, created_at, id) WHERE status = 'READY';
CREATE INDEX runtime_outbox_expired ON "${deliverySchema}".runtime_outbox (lease_until) WHERE status = 'IN_PROGRESS';
