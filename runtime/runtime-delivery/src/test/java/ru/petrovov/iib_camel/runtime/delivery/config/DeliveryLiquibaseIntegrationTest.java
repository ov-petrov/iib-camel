package ru.petrovov.iib_camel.runtime.delivery.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import ru.petrovov.iib_camel.runtime.delivery.jdbc.DeliveryTables;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "IIB_TEST_POSTGRES_URL", matches = ".+")
class DeliveryLiquibaseIntegrationTest {
    private static final String CHANGE_LOG = "classpath:db/changelog/db.changelog-master.yaml";
    private static final String SQL_RESOURCE = "db/changelog/sql/001-runtime-delivery.sql";
    private final List<DeliveryTables> ownedSchemas = new ArrayList<>();
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void connect() {
        dataSource = new DriverManagerDataSource(System.getenv("IIB_TEST_POSTGRES_URL"),
            System.getenv().getOrDefault("IIB_TEST_POSTGRES_USER", "postgres"),
            System.getenv().getOrDefault("IIB_TEST_POSTGRES_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void removeOwnedSchemas() {
        for (DeliveryTables tables : ownedSchemas) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + tables.qualifiedSchema() + " CASCADE");
        }
    }

    @Test
    void optInCreatesSchemaBeforeLiquibaseHistoryAndRepeatedStartupRetainsData() {
        DeliveryTables tables = newSchema();
        assertThat(schemaExists(tables.schema())).isFalse();

        contexts(tables, true).run(context -> {
            assertThat(context).hasNotFailed();
            assertMigrationTables(tables);
            insertInbox(tables);
            assertThat(changeSetCount(tables)).isEqualTo(1);
        });

        contexts(tables, true).run(context -> {
            assertThat(context).hasNotFailed();
            assertMigrationTables(tables);
            assertThat(inboxCount(tables)).isEqualTo(1);
            assertThat(changeSetCount(tables)).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.liquibase.default-schema", "spring.liquibase.liquibase-schema",
        "spring.liquibase.parameters.deliverySchema"})
    void conflictingSchemaSettingsFailBeforeCreatingEitherSchema(String property) {
        DeliveryTables tables = newSchema();
        DeliveryTables other = newSchema();

        contexts(tables, true).withPropertyValues(property + "=" + other.schema()).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasStackTraceContaining(property + " must match iib.delivery.schema");
        });

        assertThat(schemaExists(tables.schema())).isFalse();
        assertThat(schemaExists(other.schema())).isFalse();
    }

    @Test
    void existingDeliveryTablesRequireExplicitAdoptionAndKeepTheirData() throws Exception {
        DeliveryTables tables = newSchema();
        jdbc.execute("CREATE SCHEMA " + tables.qualifiedSchema());
        String sql = new ClassPathResource(SQL_RESOURCE).getContentAsString(StandardCharsets.UTF_8)
            .replace("${deliverySchema}", tables.schema());
        jdbc.execute(sql);
        insertInbox(tables);

        contexts(tables, true).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("already exists");
        });

        assertThat(inboxCount(tables)).isEqualTo(1);
        assertThat(tableExists(tables.schema(), "runtime_outbox")).isTrue();
        assertThat(changeSetCount(tables)).isZero();
    }

    @Test
    void preProvisionedSchemaMigratesWithoutSchemaCreationOptIn() {
        DeliveryTables tables = newSchema();
        jdbc.execute("CREATE SCHEMA " + tables.qualifiedSchema());

        contexts(tables, false).run(context -> {
            assertThat(context).hasNotFailed();
            assertMigrationTables(tables);
            assertThat(changeSetCount(tables)).isEqualTo(1);
        });
    }

    @Test
    void absentSchemaIsNotCreatedWithoutOptIn() {
        DeliveryTables tables = newSchema();

        contexts(tables, false).run(context -> assertThat(context).hasFailed());

        assertThat(schemaExists(tables.schema())).isFalse();
    }

    private ApplicationContextRunner contexts(DeliveryTables tables, boolean createSchema) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DeliveryLiquibaseAutoConfiguration.class,
                LiquibaseAutoConfiguration.class))
            .withBean(DataSource.class, () -> dataSource)
            .withPropertyValues("iib.delivery.enabled=true", "iib.delivery.schema=" + tables.schema(),
                "iib.delivery.create-schema=" + createSchema, "spring.liquibase.change-log=" + CHANGE_LOG,
                "spring.liquibase.default-schema=" + tables.schema(),
                "spring.liquibase.liquibase-schema=" + tables.schema(),
                "spring.liquibase.parameters.deliverySchema=" + tables.schema(),
                "spring.liquibase.analytics-enabled=false");
    }

    private DeliveryTables newSchema() {
        DeliveryTables tables = new DeliveryTables("liquibase_test_" + UUID.randomUUID().toString().replace("-", ""));
        ownedSchemas.add(tables);
        return tables;
    }

    private boolean schemaExists(String schema) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = ?)", Boolean.class, schema));
    }

    private boolean tableExists(String schema, String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?
            )
            """, Boolean.class, schema, table));
    }

    private void assertMigrationTables(DeliveryTables tables) {
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
            String.class, tables.schema()))
            .containsExactlyInAnyOrder("runtime_inbox", "runtime_outbox", "databasechangelog",
                "databasechangeloglock");
    }

    private void insertInbox(DeliveryTables tables) {
        jdbc.update("INSERT INTO " + tables.inbox() + " (module_id, source, message_id, payload_hash) VALUES (?, ?, ?, ?)",
            "synthetic-module", "synthetic-source", "synthetic-message", new byte[]{1, 2, 3});
    }

    private int inboxCount(DeliveryTables tables) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tables.inbox(), Integer.class);
    }

    private int changeSetCount(DeliveryTables tables) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tables.qualifiedSchema()
            + ".databasechangelog WHERE id = ?", Integer.class, "runtime-delivery-001");
    }
}
