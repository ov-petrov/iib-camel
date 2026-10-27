ALTER TABLE ${deliverySchema}.outbox_message
    ADD COLUMN broker_id TEXT;

UPDATE ${deliverySchema}.outbox_message
SET broker_id = CASE transport
                    WHEN 'JMS' THEN 'default-mq'
                    WHEN 'KAFKA' THEN 'default-kafka'
    END;

ALTER TABLE ${deliverySchema}.outbox_message
    ALTER COLUMN broker_id SET NOT NULL;
