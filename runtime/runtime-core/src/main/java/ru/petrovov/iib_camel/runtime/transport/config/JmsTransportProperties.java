package ru.petrovov.iib_camel.runtime.transport.config;

public abstract class JmsTransportProperties {
    private boolean enabled;
    private String username;
    private String password;
    private int maxConnections = 4;
    private int maxSessionsPerConnection = 32;
    private final TlsProperties tls = new TlsProperties();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
    public int getMaxSessionsPerConnection() { return maxSessionsPerConnection; }
    public void setMaxSessionsPerConnection(int maxSessionsPerConnection) { this.maxSessionsPerConnection = maxSessionsPerConnection; }
    public TlsProperties getTls() { return tls; }

    public void validate() {
        if (maxConnections < 1 || maxSessionsPerConnection < 1) {
            throw new IllegalArgumentException("JMS pool limits must be positive");
        }
        tls.validate();
    }

    protected static void required(String value, String property) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(property + " is required when enabled");
    }

    @Override public String toString() {
        return getClass().getSimpleName() + "[enabled=" + enabled + ", credentials=<redacted>]";
    }
}
