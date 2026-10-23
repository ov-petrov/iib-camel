package ru.petrovov.iib_camel.runtime.transport.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@Getter
@Setter
public abstract class JmsTransportProperties {
    private boolean enabled;
    private String username;
    private String password;
    private int maxConnections = 4;
    private int maxSessionsPerConnection = 32;
    @NestedConfigurationProperty
    private final TlsProperties tls = new TlsProperties();

    public void validate() {
        if (maxConnections < 1 || maxSessionsPerConnection < 1) {
            throw new IllegalArgumentException("JMS pool limits must be positive");
        }
        tls.validate();
    }

    protected static void required(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " is required when enabled");
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[enabled=" + enabled + ", credentials=<redacted>]";
    }
}
