package ru.petrovov.iib_camel.runtime_lite.config;

import org.junit.jupiter.api.Test;
import ru.petrovov.iib_camel.runtime_lite.jdbc.DeliveryTables;
import ru.petrovov.iib_camel.runtime_lite.transport.BrokerRegistry;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;

class RoutingConfigurationTest {

    @Test
    void rejectsMissingDestinationBeforeStartingListeners() {
        var properties = new RoutingProperties();
        var route = new RoutingProperties.Route();
        route.setOutput("missing");
        properties.getRoutes().put("sample", route);

        assertThatIllegalArgumentException().isThrownBy(() -> new RoutingCatalog(properties, mock(BrokerRegistry.class)).validate())
            .withMessageContaining("Unknown destination");
    }

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

        assertThatIllegalArgumentException().isThrownBy(() -> new DeliveryTables(properties));
    }
}
