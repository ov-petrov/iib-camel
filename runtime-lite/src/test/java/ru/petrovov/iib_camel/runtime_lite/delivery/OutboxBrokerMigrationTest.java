package ru.petrovov.iib_camel.runtime_lite.delivery;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import ru.petrovov.iib_camel.runtime_lite.outbox.OutboxStatus;
import ru.petrovov.iib_camel.runtime_lite.transport.BrokerIds;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "IIB_LITE_TEST_POSTGRES_URL", matches = ".+")
class OutboxBrokerMigrationTest {

    @Test
    void upgradesExistingRowsAndPreservesTheirDeliveryState() throws Exception {
        var dataSource = new DriverManagerDataSource();
        dataSource.setUrl(System.getenv("IIB_LITE_TEST_POSTGRES_URL"));
        dataSource.setUsername(System.getenv("IIB_LITE_TEST_POSTGRES_USER"));
        dataSource.setPassword(System.getenv("IIB_LITE_TEST_POSTGRES_PASSWORD"));
        var jdbc = new JdbcTemplate(dataSource);
        String schema = "lite_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String table = schema + ".outbox_message";
        UUID mqId = UUID.randomUUID();
        UUID kafkaId = UUID.randomUUID();
        UUID sentId = UUID.randomUUID();
        byte[] body = "synthetic payload".getBytes(StandardCharsets.UTF_8);

        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            var connection = dataSource.getConnection();
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setDefaultSchemaName(schema);
            database.setLiquibaseSchemaName(schema);
            try (var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
                new ClassLoaderResourceAccessor(), database)) {
                liquibase.setChangeLogParameter("deliverySchema", schema);
                liquibase.update(1, new Contexts(), new LabelExpression());

                insertLegacy(jdbc, table, mqId, Transport.JMS, OutboxStatus.PENDING, body, 2);
                insertLegacy(jdbc, table, kafkaId, Transport.KAFKA, OutboxStatus.FAILED, body, 5);
                insertLegacy(jdbc, table, sentId, Transport.JMS, OutboxStatus.SENT, body, 1);

                liquibase.update(new Contexts(), new LabelExpression());
            }

            assertLegacyRow(jdbc, table, mqId, Transport.JMS, BrokerIds.DEFAULT_MQ, OutboxStatus.PENDING, body, 2);
            assertLegacyRow(jdbc, table, kafkaId, Transport.KAFKA, BrokerIds.DEFAULT_KAFKA,
                OutboxStatus.FAILED, body, 5);
            assertLegacyRow(jdbc, table, sentId, Transport.JMS, BrokerIds.DEFAULT_MQ, OutboxStatus.SENT, body, 1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".databasechangelog", Integer.class))
                .isEqualTo(2);
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static void insertLegacy(JdbcTemplate jdbc, String table, UUID id, Transport transport,
                                     OutboxStatus status, byte[] body, int attempts) {
        jdbc.update("INSERT INTO " + table + " " + """
                (id, transport, destination, body, message_key, headers, status, attempts)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """, id, transport.name(), "synthetic.destination", body, "synthetic-key",
            "{\"sample\":\"value\"}", status.name(), attempts);
    }

    private static void assertLegacyRow(JdbcTemplate jdbc, String table, UUID id, Transport transport,
                                        String broker, OutboxStatus status, byte[] body, int attempts) {
        var row = jdbc.queryForMap("SELECT transport, broker_id, destination, body, message_key, headers, status, attempts "
            + "FROM " + table + " WHERE id = ?", id);
        assertThat(row).containsEntry("transport", transport.name())
            .containsEntry("broker_id", broker)
            .containsEntry("destination", "synthetic.destination")
            .containsEntry("message_key", "synthetic-key")
            .containsEntry("status", status.name())
            .containsEntry("attempts", attempts);
        assertThat((byte[]) row.get("body")).isEqualTo(body);
        assertThat(row.get("headers").toString()).contains("sample", "value");
    }
}
