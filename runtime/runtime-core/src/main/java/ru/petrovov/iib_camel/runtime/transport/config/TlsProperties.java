package ru.petrovov.iib_camel.runtime.transport.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public final class TlsProperties {
    private boolean enabled;
    private String trustStore;
    private String trustStorePassword;
    private String trustStoreType = "PKCS12";
    private String keyStore;
    private String keyStorePassword;
    private String keyStoreType = "PKCS12";
    private String keyPassword;

    void validate() {
        if (!enabled && (trustStore != null || keyStore != null)) {
            throw new IllegalArgumentException("Enable TLS before configuring JMS key or trust stores");
        }
    }

    @Override
    public String toString() {
        return "TlsProperties[enabled=" + enabled + ", secrets=<redacted>]";
    }
}
