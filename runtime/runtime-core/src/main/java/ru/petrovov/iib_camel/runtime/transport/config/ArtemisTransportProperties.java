package ru.petrovov.iib_camel.runtime.transport.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("iib.transports.artemis")
public final class ArtemisTransportProperties extends JmsTransportProperties {
    private String brokerUrl;

    @Override
    public void validate() {
        super.validate();
        required(brokerUrl, "iib.transports.artemis.broker-url");
        if (!brokerUrl.startsWith("tcp://")) {
            throw new IllegalArgumentException("Artemis broker-url must use tcp://");
        }
        if (getTls().getKeyPassword() != null
            && !getTls().getKeyPassword().equals(getTls().getKeyStorePassword())) {
            throw new IllegalArgumentException("Artemis requires the key password to match the key-store password");
        }
    }
}
