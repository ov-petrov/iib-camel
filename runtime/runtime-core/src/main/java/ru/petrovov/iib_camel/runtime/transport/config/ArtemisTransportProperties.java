package ru.petrovov.iib_camel.runtime.transport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("iib.transports.artemis")
public final class ArtemisTransportProperties extends JmsTransportProperties {
    private String brokerUrl;

    public String getBrokerUrl() { return brokerUrl; }
    public void setBrokerUrl(String brokerUrl) { this.brokerUrl = brokerUrl; }

    @Override public void validate() {
        super.validate();
        required(brokerUrl, "iib.transports.artemis.broker-url");
        if (!brokerUrl.startsWith("tcp://")) {
            throw new IllegalArgumentException("Artemis broker-url must use tcp://");
        }
        if (getTls().getKeyPassword() != null && !getTls().getKeyPassword().equals(getTls().getKeyStorePassword())) {
            throw new IllegalArgumentException("Artemis requires the key password to match the key-store password");
        }
    }
}
