package ru.petrovov.iib_camel.runtime_lite.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import ru.petrovov.iib_camel.runtime_lite.jdbc.DeliveryTables;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class RoutingConfigurationTest {

    @Test
    void deliveryTablesAlwaysUseValidatedExplicitSchema() {
        assertThat(new DeliveryTables("custom_delivery").outbox()).isEqualTo("custom_delivery.outbox_message");
        assertThatIllegalArgumentException().isThrownBy(() -> new DeliveryTables("public; DROP SCHEMA public"));
        assertThatIllegalArgumentException().isThrownBy(() -> new DeliveryTables("MixedCase"));
    }

    @Test
    void rejectsNonPositivePollingAndRetryDurations() {
        var properties = new OutboxProperties();
        properties.setRetryDelay(Duration.ZERO);

        var liquibase = new LiquibaseProperties();
        liquibase.setDefaultSchema("runtime_lite");

        assertThatIllegalArgumentException().isThrownBy(() -> new DeliveryTables(liquibase, properties));
    }

    @Test
    void rejectsConflictingConnectionAndLiquibaseSchemas() {
        var configuration = new InfrastructureConfiguration();
        var dataSource = new HikariDataSource();
        var tables = new DeliveryTables("runtime_lite");
        var liquibase = new LiquibaseProperties();
        dataSource.setSchema("other_schema");

        assertThatIllegalStateException()
            .isThrownBy(() -> configuration.liquibase(dataSource, tables, liquibase))
            .withMessageContaining("DataSource schema");

        dataSource.setSchema("runtime_lite");
        liquibase.setLiquibaseSchema("other_schema");
        assertThatIllegalStateException()
            .isThrownBy(() -> configuration.liquibase(dataSource, tables, liquibase))
            .withMessageContaining("Liquibase schema");
    }
}
