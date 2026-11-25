package ru.petrovov.iib_camel.runtime_lite.config.soap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.petrovov.iib_camel.runtime_lite.config.transport.ListenerProperties;
import ru.petrovov.iib_camel.runtime_lite.soap.SoapService;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsMessageReader;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsPayloadCodec;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsPayloadFormat;

import static org.assertj.core.api.Assertions.assertThat;

class SoapConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(SoapConfiguration.class)
        .withBean(JmsMessageReader.class, () -> new JmsMessageReader(new ListenerProperties()));

    @Test
    void createsServicesWithSoapForAllJmsDestinationsByDefault() {
        context.run(result -> {
            assertThat(result).hasNotFailed().hasSingleBean(SoapService.class).hasSingleBean(JmsPayloadCodec.class);
            assertThat(result.getBean(JmsPayloadCodec.class).formatFor("SAMPLE.INPUT"))
                .isEqualTo(JmsPayloadFormat.SOAP_11);
        });
    }

    @Test
    void bindsPlainDestinationsAndLimits() {
        context.withPropertyValues("runtime-lite.soap.plain-jms-destinations[0]=SAMPLE.INPUT",
            "runtime-lite.soap.max-depth=16", "runtime-lite.soap.max-elements=100")
            .run(result -> {
                assertThat(result).hasNotFailed();
                assertThat(result.getBean(JmsPayloadCodec.class).formatFor("SAMPLE.INPUT"))
                    .isEqualTo(JmsPayloadFormat.PLAIN);
                assertThat(result.getBean(JmsPayloadCodec.class).formatFor("SAMPLE.OTHER"))
                    .isEqualTo(JmsPayloadFormat.SOAP_11);
                var properties = result.getBean(SoapProperties.class);
                assertThat(properties.getMaxDepth()).isEqualTo(16);
                assertThat(properties.getMaxElements()).isEqualTo(100);
            });
    }

    @Test
    void rejectsInvalidLimitsAndBlankDestinations() {
        context.withPropertyValues("runtime-lite.soap.max-depth=2")
            .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("runtime-lite.soap.max-elements=0")
            .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("runtime-lite.soap.plain-jms-destinations[0]= ")
            .run(result -> assertThat(result).hasFailed());
    }

    @Test
    void rejectsTheRemovedPositiveListProperty() {
        context.withPropertyValues("runtime-lite.soap.jms-destinations[0]=SAMPLE.INPUT")
            .run(result -> assertThat(result).hasFailed());
    }

    @Test
    void acceptsAnApplicationSoapService() {
        var service = new SoapService(1000, 16, 100);
        context.withBean(SoapService.class, () -> service).run(result -> {
            assertThat(result).hasNotFailed().hasSingleBean(SoapService.class);
            assertThat(result.getBean(SoapService.class)).isSameAs(service);
        });
    }
}
