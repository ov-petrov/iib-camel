package ru.petrovov.iib_camel.runtime_lite.config;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import ru.petrovov.iib_camel.runtime_lite.jdbc.DeliveryTables;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@EnableScheduling
public class InfrastructureConfiguration {

    @Bean
    JdbcTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    TransactionTemplate outboxTransaction(JdbcTransactionManager transactionManager) {
        var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Bean
    SpringLiquibase liquibase(DataSource dataSource, DeliveryTables tables) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + tables.schema());
        }
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setDefaultSchema(tables.schema());
        liquibase.setLiquibaseSchema(tables.schema());
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.setChangeLogParameters(Map.of("deliverySchema", tables.schema()));
        return liquibase;
    }
}
