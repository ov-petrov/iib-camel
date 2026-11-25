CREATE TABLE IF NOT EXISTS ${deliverySchema}.enrichment_workflow (
    id UUID PRIMARY KEY,
    route_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    operation_key TEXT NOT NULL,
    correlation_id TEXT NOT NULL UNIQUE,
    reply_queue TEXT NOT NULL,
    request_outbox_id UUID NOT NULL UNIQUE REFERENCES ${deliverySchema}.outbox_message (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    dispatch_deadline TIMESTAMPTZ NOT NULL,
    reply_timeout_ms BIGINT NOT NULL CHECK (reply_timeout_ms > 0),
    sent_at TIMESTAMPTZ,
    reply_deadline TIMESTAMPTZ,
    reply_received_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    reply_message_id TEXT,
    reply_body TEXT,
    reply_xml XML,
    reply_headers JSONB,
    status TEXT NOT NULL CHECK (status IN ('PENDING_SEND', 'WAITING_REPLY', 'REPLY_RECEIVED', 'COMPLETED', 'FAILED')),
    failure_code TEXT,
    result_outbox_id UUID UNIQUE REFERENCES ${deliverySchema}.outbox_message (id),
    CHECK (reply_body IS NULL OR reply_xml IS NULL),
    UNIQUE (route_id, message_id),
    FOREIGN KEY (route_id, message_id) REFERENCES ${deliverySchema}.inbound_message (route_id, message_id)
);

CREATE INDEX IF NOT EXISTS enrichment_workflow_ready_idx
    ON ${deliverySchema}.enrichment_workflow (status, reply_received_at, id);

CREATE INDEX IF NOT EXISTS enrichment_workflow_dispatch_deadline_idx
    ON ${deliverySchema}.enrichment_workflow (status, dispatch_deadline, id);

CREATE INDEX IF NOT EXISTS enrichment_workflow_reply_deadline_idx
    ON ${deliverySchema}.enrichment_workflow (status, reply_deadline, id);
