package ru.petrovov.iib_camel.runtime.delivery.config;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import ru.petrovov.iib_camel.runtime.delivery.inbox.InboxService;
import ru.petrovov.iib_camel.runtime.delivery.outbox.OutboxDispatcher;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.*;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeDeliveryAutoConfigurationTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JdbcTemplateAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class, RuntimeDeliveryAutoConfiguration.class));

    @Test
    void disabledDeliveryNeedsNoDatabaseOrScheduler() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(InboxService.class);
            assertThat(context).doesNotHaveBean(OutboxDispatcher.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }

    @Test
    void enabledDeliveryRequiresDataSource() {
        contexts.withPropertyValues("iib.delivery.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("JdbcTemplate");
        });
    }

    @Test
    void keepsBootJdbcTemplateAndReusesJdbcTransactionManager() throws Exception {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setFetchSize(17);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);

        contexts.withBean(DataSource.class, () -> dataSource)
            .withBean(JdbcTemplate.class, () -> jdbc)
            .withBean(DataSourceTransactionManager.class, () -> manager)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withPropertyValues("iib.delivery.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(JdbcTemplate.class)).isSameAs(jdbc);
                assertThat(context.getBean(JdbcTemplate.class).getFetchSize()).isEqualTo(17);
                TransactionTemplate deliveryTemplate = context.getBean("deliveryTransactionTemplate",
                    TransactionTemplate.class);
                assertThat(deliveryTemplate.getTransactionManager()).isSameAs(manager);
                assertThat(deliveryTemplate.getPropagationBehavior())
                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            });
    }

    @Test
    void selectsJdbcTransactionManagerWhenAnotherManagerIsPresent() throws Exception {
        DataSource dataSource = dataSource();
        PlatformTransactionManager otherManager = new NoopTransactionManager();

        contexts.withBean(DataSource.class, () -> dataSource)
            .withBean("jmsTransactionManager", PlatformTransactionManager.class, () -> otherManager)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withPropertyValues("iib.delivery.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                TransactionTemplate deliveryTemplate = context.getBean("deliveryTransactionTemplate",
                    TransactionTemplate.class);
                assertThat(deliveryTemplate.getTransactionManager())
                    .isInstanceOf(DataSourceTransactionManager.class);
                assertThat(deliveryTemplate.getTransactionManager()).isNotSameAs(otherManager);
                assertThat(context.getBean(JdbcTemplate.class).getDataSource()).isSameAs(dataSource);
            });
    }

    @Test
    void rejectsTransactionManagerForDifferentDataSource() throws Exception {
        DataSource dataSource = dataSource();
        DataSource otherDataSource = dataSource();
        contexts.withBean(DataSource.class, () -> dataSource)
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
            .withBean(DataSourceTransactionManager.class,
                () -> new DataSourceTransactionManager(otherDataSource))
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withPropertyValues("iib.delivery.enabled=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("DataSource");
            });
    }

    @Test
    void rejectsInvalidSchemaBeforeCreatingDeliveryServices() throws Exception {
        DataSource dataSource = dataSource();
        contexts.withBean(DataSource.class, () -> dataSource)
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
            .withBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withPropertyValues("iib.delivery.enabled=true", "iib.delivery.schema=Invalid_schema")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("schema");
            });
    }

    @Test
    void rejectsNamedDeliveryTransactionTemplateWithRequiredPropagation() throws Exception {
        DataSource dataSource = dataSource();
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        TransactionTemplate requiredTransaction = new TransactionTemplate(manager);

        contexts.withBean(DataSource.class, () -> dataSource)
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
            .withBean(DataSourceTransactionManager.class, () -> manager)
            .withBean(RuntimeDeliveryAutoConfiguration.TRANSACTION_TEMPLATE,
                TransactionTemplate.class, () -> requiredTransaction)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withPropertyValues("iib.delivery.enabled=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("REQUIRES_NEW");
            });
    }

    private static DataSource dataSource() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        DatabaseMetaData metadata = Mockito.mock(DatabaseMetaData.class);
        PreparedStatement statement = Mockito.mock(PreparedStatement.class);
        ResultSet resultSet = Mockito.mock(ResultSet.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.getMetaData()).thenReturn(metadata);
        Mockito.when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(statement);
        Mockito.when(statement.executeQuery()).thenReturn(resultSet);
        Mockito.when(resultSet.next()).thenReturn(false);
        return dataSource;
    }

    private static final class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
