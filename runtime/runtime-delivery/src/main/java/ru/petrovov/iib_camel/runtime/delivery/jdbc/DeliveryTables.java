package ru.petrovov.iib_camel.runtime.delivery.jdbc;

import java.util.regex.Pattern;

public record DeliveryTables(String schema) {
    private static final Pattern SCHEMA_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final String INBOX = "runtime_inbox";
    private static final String OUTBOX = "runtime_outbox";

    public DeliveryTables {
        if (schema == null || !SCHEMA_NAME.matcher(schema).matches()) {
            throw new IllegalArgumentException(
                "Delivery schema must be a lowercase PostgreSQL identifier of up to 63 ASCII characters");
        }
    }

    public String qualifiedSchema() {
        return '"' + schema + '"';
    }

    public String inbox() {
        return qualifiedSchema() + ".\"" + INBOX + '"';
    }

    public String outbox() {
        return qualifiedSchema() + ".\"" + OUTBOX + '"';
    }
}
