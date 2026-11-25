CREATE TABLE IF NOT EXISTS ${deliverySchema}.inbound_message (
    route_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    source_destination TEXT NOT NULL,
    processor_id TEXT NOT NULL,
    body TEXT,
    body_xml XML,
    headers JSONB NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (route_id, message_id),
    CHECK ((body IS NULL) <> (body_xml IS NULL))
);
