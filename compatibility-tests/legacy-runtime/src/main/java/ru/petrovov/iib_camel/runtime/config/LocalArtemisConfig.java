package ru.petrovov.iib_camel.runtime.config;

import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.springframework.boot.artemis.autoconfigure.ArtemisConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalArtemisConfig {
    @Bean
    public ArtemisConfigurationCustomizer applicationOwnedRedelivery() {
        return configuration -> configuration.addAddressSetting("#", new AddressSettings().setMaxDeliveryAttempts(-1));
    }
}
