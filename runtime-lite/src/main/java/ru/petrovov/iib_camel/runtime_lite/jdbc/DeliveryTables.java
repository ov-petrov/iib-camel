package ru.petrovov.iib_camel.runtime_lite.jdbc;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.stereotype.Component;
import ru.petrovov.iib_camel.runtime_lite.config.OutboxProperties;

@Component
public record DeliveryTables(String schema) {

    @Autowired
    public DeliveryTables(LiquibaseProperties liquibase, OutboxProperties outbox) {
        this(liquibase.getDefaultSchema());
        outbox.validateDurations();
    }

    public DeliveryTables {
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Invalid delivery schema");
        }
    }

    public String receipts() {
        return schema + ".inbound_receipt";
    }

    public String outbox() {
        return schema + ".outbox_message";
    }
}
