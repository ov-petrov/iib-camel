package ru.petrovov.iib_camel.runtime.delivery.config;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.petrovov.iib_camel.runtime.delivery.inbox.InboxService;
import ru.petrovov.iib_camel.runtime.delivery.jdbc.DeliveryTables;
import ru.petrovov.iib_camel.runtime.delivery.jdbc.HeaderCodec;
import ru.petrovov.iib_camel.runtime.delivery.jdbc.PostgresDatabase;
import ru.petrovov.iib_camel.runtime.delivery.outbox.OutboxDispatcher;
import ru.petrovov.iib_camel.runtime.delivery.outbox.OutboxStore;
import ru.petrovov.iib_camel.runtime.transport.TransportSender;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

@AutoConfiguration(afterName = {
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration",
    "ru.petrovov.iib_camel.runtime.transport.config.RuntimeTransportAutoConfiguration",
    "ru.petrovov.iib_camel.runtime.xml.RuntimeXmlAutoConfiguration"})
@ConditionalOnProperty(prefix = "iib.delivery", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DeliveryProperties.class)
public class RuntimeDeliveryAutoConfiguration {
    public static final String TRANSACTION_TEMPLATE = "deliveryTransactionTemplate";

    @Bean
    @ConditionalOnMissingBean
    @DependsOnDatabaseInitialization
    DeliveryTables deliveryTables(JdbcTemplate jdbc, DeliveryProperties properties) {
        DeliveryTables tables = new DeliveryTables(properties.getSchema());
        properties.validate();
        PostgresDatabase.require(Objects.requireNonNull(jdbc.getDataSource(), "JDBC DataSource"));
        return tables;
    }

    @Bean
    @ConditionalOnMissingBean
    HeaderCodec headerCodec(JsonMapper mapper) {
        return new HeaderCodec(mapper);
    }

    @Bean
    @ConditionalOnMissingBean(DataSourceTransactionManager.class)
    DataSourceTransactionManager deliveryJdbcTransactionManager(JdbcTemplate jdbc) {
        return new JdbcTransactionManager(Objects.requireNonNull(jdbc.getDataSource(), "JDBC DataSource"));
    }

    @Bean(TRANSACTION_TEMPLATE)
    @ConditionalOnMissingBean(name = TRANSACTION_TEMPLATE)
    TransactionTemplate deliveryTransactionTemplate(JdbcTemplate jdbc, DataSourceTransactionManager manager) {
        DataSource dataSource = Objects.requireNonNull(jdbc.getDataSource(), "JDBC DataSource");
        if (manager.getDataSource() != dataSource) {
            throw new IllegalArgumentException("Delivery JdbcTemplate and JDBC transaction manager must use the same DataSource");
        }
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    @Bean
    @ConditionalOnMissingBean
    @DependsOnDatabaseInitialization
    InboxService inboxService(JdbcTemplate jdbc, @Qualifier(TRANSACTION_TEMPLATE) TransactionTemplate transaction,
                              HeaderCodec headers, DeliveryTables tables) {
        validateTransaction(jdbc, transaction);
        return new InboxService(jdbc, transaction, headers, tables);
    }

    @Bean
    @ConditionalOnMissingBean
    @DependsOnDatabaseInitialization
    OutboxStore outboxStore(JdbcTemplate jdbc, @Qualifier(TRANSACTION_TEMPLATE) TransactionTemplate transaction,
                            HeaderCodec headers, DeliveryProperties properties, DeliveryTables tables) {
        validateTransaction(jdbc, transaction);
        return new OutboxStore(jdbc, transaction, headers, properties, tables);
    }

    @Bean
    @ConditionalOnMissingBean
    @DependsOnDatabaseInitialization
    OutboxDispatcher outboxDispatcher(OutboxStore store, DeliveryProperties properties, List<TransportSender> senders) {
        return new OutboxDispatcher(store, properties, senders);
    }

    private static void validateTransaction(JdbcTemplate jdbc, TransactionTemplate transaction) {
        if (!(transaction.getTransactionManager() instanceof DataSourceTransactionManager manager)
            || manager.getDataSource() != jdbc.getDataSource()) {
            throw new IllegalArgumentException("Delivery transaction template must use the JdbcTemplate DataSource");
        }
        if (transaction.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
            throw new IllegalArgumentException("Delivery transaction template must use REQUIRES_NEW");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "iib.delivery", name = "publisher-enabled", havingValue = "true")
    @EnableScheduling
    static class SchedulingConfiguration {
        @Bean
        OutboxSchedule outboxSchedule(OutboxDispatcher dispatcher) {
            return new OutboxSchedule(dispatcher);
        }
    }

    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class OutboxSchedule {
        private final OutboxDispatcher dispatcher;

        @Scheduled(fixedDelayString = "${iib.delivery.scheduler.fixed-delay:PT1S}")
        public void dispatch() {
            dispatcher.dispatchOnce();
        }
    }
}
