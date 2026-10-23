package ru.petrovov.iib_camel.runtime.transport.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import({
    ArtemisTransportConfiguration.class,
    IbmMqTransportConfiguration.class,
    KafkaTransportConfiguration.class
})
public class RuntimeTransportAutoConfiguration {
}
