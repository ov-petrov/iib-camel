ALTER TABLE ${deliverySchema}.enrichment_workflow
    ADD COLUMN ready_at TIMESTAMPTZ,
    DROP CONSTRAINT enrichment_workflow_status_check;

UPDATE ${deliverySchema}.enrichment_workflow
SET status = 'READY_TO_MAP', ready_at = reply_received_at
WHERE status = 'REPLY_RECEIVED';

ALTER TABLE ${deliverySchema}.enrichment_workflow
    ADD CONSTRAINT enrichment_workflow_status_check
    CHECK (status IN ('PENDING_SEND', 'WAITING_REPLY', 'READY_TO_MAP', 'COMPLETED', 'FAILED'));

DROP INDEX ${deliverySchema}.enrichment_workflow_ready_idx;

CREATE INDEX enrichment_workflow_ready_idx
    ON ${deliverySchema}.enrichment_workflow (status, ready_at, id);
