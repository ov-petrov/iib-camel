package ru.petrovov.iib_camel.runtime.delivery.jdbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryTablesTest {
    @Test
    void qualifiesRuntimeTablesWithTheValidatedSchema() {
        DeliveryTables tables = new DeliveryTables("delivery_schema_9");

        assertThat(tables.qualifiedSchema()).isEqualTo("\"delivery_schema_9\"");
        assertThat(tables.inbox()).isEqualTo("\"delivery_schema_9\".\"runtime_inbox\"");
        assertThat(tables.outbox()).isEqualTo("\"delivery_schema_9\".\"runtime_outbox\"");
    }

    @Test
    void acceptsMaximumLengthLowercasePostgresIdentifier() {
        String schema = "a" + "b".repeat(62);

        assertThat(new DeliveryTables(schema).schema()).isEqualTo(schema);
    }

    @Test
    void rejectsIdentifiersOutsideTheLowercaseAsciiContract() {
        assertThrows(IllegalArgumentException.class, () -> new DeliveryTables("Delivery_schema"));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryTables("delivery-schema"));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryTables("9delivery"));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryTables("a" + "b".repeat(63)));
    }
}
