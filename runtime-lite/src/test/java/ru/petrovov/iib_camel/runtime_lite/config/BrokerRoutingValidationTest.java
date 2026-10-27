package ru.petrovov.iib_camel.runtime_lite.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import ru.petrovov.iib_camel.runtime_lite.transport.config.TransportConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class BrokerRoutingValidationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(TransportConfiguration.class, RoutingConfiguration.class);

    @Test
    void rejectsUnknownInputBrokerDuringStartup() {
        context.withPropertyValues(
                "runtime-lite.routes.sample.broker=missing",
                "runtime-lite.routes.sample.input-queue=SAMPLE.IN",
                "runtime-lite.routes.sample.processor=sampleProcessor",
                "runtime-lite.routes.sample.output=result",
                "runtime-lite.destinations.result.transport=KAFKA",
                "runtime-lite.destinations.result.name=sample.result")
            .run(result -> {
                assertThat(result).hasFailed();
                assertThat(result.getStartupFailure()).hasRootCauseMessage("Unknown JMS broker: missing");
            });
    }

    @Test
    void rejectsDestinationWhenBrokerHasTheWrongTransportType() {
        context.withPropertyValues(
                "runtime-lite.transports.kafka.kafka-a.bootstrap-servers=localhost:9092",
                "runtime-lite.destinations.result.transport=JMS",
                "runtime-lite.destinations.result.broker=kafka-a",
                "runtime-lite.destinations.result.name=SAMPLE.OUT")
            .run(result -> {
                assertThat(result).hasFailed();
                assertThat(result.getStartupFailure()).hasRootCauseMessage("Unknown JMS broker: kafka-a");
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RoutingProperties.class)
    @Import(RoutingCatalog.class)
    static class RoutingConfiguration {
    }
}
