package ru.petrovov.iib_camel.runtime.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class RuntimeDeliveryAutoConfigurationTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RuntimeDeliveryAutoConfiguration.class));

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
            assertThat(context.getStartupFailure()).hasMessageContaining("DataSource");
        });
    }
}
