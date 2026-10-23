package ru.petrovov.iib_camel.runtime.delivery.config;

import liquibase.Liquibase;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.integration.spring.Customizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.petrovov.iib_camel.runtime.delivery.jdbc.DeliveryTables;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@AutoConfiguration(beforeName = "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration")
@ConditionalOnProperty(prefix = "iib.delivery", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DeliveryProperties.class)
public class DeliveryLiquibaseAutoConfiguration {
    private static final String SCHEMA_PARAMETER = "deliverySchema";

    @Bean
    Customizer<Liquibase> deliverySchemaCustomizer(DeliveryProperties properties) {
        DeliveryTables tables = new DeliveryTables(properties.getSchema());
        return liquibase -> {
            validateSchema(liquibase, tables);
            if (properties.isCreateSchema()) {
                createSchema(liquibase, tables);
            }
        };
    }

    private static void validateSchema(Liquibase liquibase, DeliveryTables tables) {
        if (!(liquibase.getDatabase() instanceof PostgresDatabase)) {
            throw new IllegalStateException("Delivery migrations require PostgreSQL");
        }

        requireSchema(tables.schema(), liquibase.getDatabase().getDefaultSchemaName(),
            "spring.liquibase.default-schema");
        requireSchema(tables.schema(), liquibase.getDatabase().getLiquibaseSchemaName(),
            "spring.liquibase.liquibase-schema");
        requireSchema(tables.schema(), liquibase.getChangeLogParameters().getValue(SCHEMA_PARAMETER, null),
            "spring.liquibase.parameters." + SCHEMA_PARAMETER);
    }

    private static void requireSchema(String schema, Object configuredSchema, String property) {
        if (!schema.equals(configuredSchema)) {
            throw new IllegalStateException(property + " must match iib.delivery.schema");
        }
    }

    private static void createSchema(Liquibase liquibase, DeliveryTables tables) {
        if (!(liquibase.getDatabase().getConnection() instanceof JdbcConnection jdbcConnection)) {
            throw new IllegalStateException("Delivery schema creation requires a JDBC connection");
        }

        Connection connection = jdbcConnection.getUnderlyingConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + tables.qualifiedSchema());
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot create delivery schema", exception);
        }
    }
}
