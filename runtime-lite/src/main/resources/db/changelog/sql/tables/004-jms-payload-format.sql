ALTER TABLE ${deliverySchema}.inbound_message
    ADD COLUMN payload_format VARCHAR(16) NOT NULL DEFAULT 'PLAIN'
        CHECK (payload_format IN ('PLAIN', 'SOAP_11'));

ALTER TABLE ${deliverySchema}.enrichment_workflow
    ADD COLUMN reply_payload_format VARCHAR(16) NOT NULL DEFAULT 'PLAIN'
        CHECK (reply_payload_format IN ('PLAIN', 'SOAP_11'));
