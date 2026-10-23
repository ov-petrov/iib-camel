package ru.petrovov.iib_camel.runtime.delivery;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import ru.petrovov.iib_camel.runtime.transport.TransportSender;
import tools.jackson.databind.ObjectMapper;

@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
@ConditionalOnProperty(prefix = "iib.delivery", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DeliveryProperties.class)
public class RuntimeDeliveryAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @DependsOnDatabaseInitialization
    InboxService inboxService(DataSource dataSource, ObjectProvider<ObjectMapper> mapper) {
        return new InboxService(dataSource, mapper.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    @ConditionalOnMissingBean
    @DependsOnDatabaseInitialization
    OutboxDispatcher outboxDispatcher(DataSource dataSource, DeliveryProperties properties,
                                      List<TransportSender> senders, ObjectProvider<ObjectMapper> mapper) {
        return new OutboxDispatcher(dataSource, properties, senders, mapper.getIfAvailable(ObjectMapper::new));
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

    static final class OutboxSchedule {
        private final OutboxDispatcher dispatcher;

        OutboxSchedule(OutboxDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @Scheduled(fixedDelayString = "${iib.delivery.scheduler.fixed-delay:PT1S}")
        public void dispatch() {
            dispatcher.dispatchOnce();
        }
    }
}
