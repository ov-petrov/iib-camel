package ru.petrovov.iib_camel.runtime.transport.config;

public final class TlsProperties {
    private boolean enabled;
    private String trustStore;
    private String trustStorePassword;
    private String trustStoreType = "PKCS12";
    private String keyStore;
    private String keyStorePassword;
    private String keyStoreType = "PKCS12";
    private String keyPassword;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTrustStore() { return trustStore; }
    public void setTrustStore(String trustStore) { this.trustStore = trustStore; }
    public String getTrustStorePassword() { return trustStorePassword; }
    public void setTrustStorePassword(String trustStorePassword) { this.trustStorePassword = trustStorePassword; }
    public String getTrustStoreType() { return trustStoreType; }
    public void setTrustStoreType(String trustStoreType) { this.trustStoreType = trustStoreType; }
    public String getKeyStore() { return keyStore; }
    public void setKeyStore(String keyStore) { this.keyStore = keyStore; }
    public String getKeyStorePassword() { return keyStorePassword; }
    public void setKeyStorePassword(String keyStorePassword) { this.keyStorePassword = keyStorePassword; }
    public String getKeyStoreType() { return keyStoreType; }
    public void setKeyStoreType(String keyStoreType) { this.keyStoreType = keyStoreType; }
    public String getKeyPassword() { return keyPassword; }
    public void setKeyPassword(String keyPassword) { this.keyPassword = keyPassword; }

    void validate() {
        if (!enabled && (trustStore != null || keyStore != null)) {
            throw new IllegalArgumentException("Enable TLS before configuring JMS key or trust stores");
        }
    }

    @Override public String toString() { return "TlsProperties[enabled=" + enabled + ", secrets=<redacted>]"; }
}
