package ru.petrovov.iib_camel.runtime.transport.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("iib.transports.kafka")
public final class KafkaTransportProperties {
    private boolean enabled;
    private String bootstrapServers;
    private String clientId = "iib-runtime";
    private Duration sendTimeout = Duration.ofSeconds(30);
    private String securityProtocol = "PLAINTEXT";
    private String saslMechanism;
    private String saslJaasConfig;
    private final TlsProperties tls = new TlsProperties();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public Duration getSendTimeout() { return sendTimeout; }
    public void setSendTimeout(Duration sendTimeout) { this.sendTimeout = sendTimeout; }
    public String getSecurityProtocol() { return securityProtocol; }
    public void setSecurityProtocol(String securityProtocol) { this.securityProtocol = securityProtocol; }
    public String getSaslMechanism() { return saslMechanism; }
    public void setSaslMechanism(String saslMechanism) { this.saslMechanism = saslMechanism; }
    public String getSaslJaasConfig() { return saslJaasConfig; }
    public void setSaslJaasConfig(String saslJaasConfig) { this.saslJaasConfig = saslJaasConfig; }
    public TlsProperties getTls() { return tls; }

    public void validate() {
        JmsTransportProperties.required(bootstrapServers, "iib.transports.kafka.bootstrap-servers");
        if (sendTimeout == null || sendTimeout.isZero() || sendTimeout.isNegative()
                || sendTimeout.toMillis() < 1 || sendTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Kafka send-timeout must be positive and fit milliseconds in an integer");
        }
        if (!Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL").contains(securityProtocol)) {
            throw new IllegalArgumentException("Unsupported Kafka security-protocol");
        }
        if (securityProtocol.startsWith("SASL")) JmsTransportProperties.required(saslMechanism, "iib.transports.kafka.sasl-mechanism");
        if (!securityProtocol.contains("SSL") && (tls.getTrustStore() != null || tls.getKeyStore() != null)) {
            throw new IllegalArgumentException("Kafka key or trust stores require an SSL security-protocol");
        }
    }

    public Map<String, Object> producerConfiguration() {
        validate();
        var config = new LinkedHashMap<String, Object>();
        config.put("bootstrap.servers", bootstrapServers);
        config.put("client.id", clientId);
        config.put("acks", "all");
        config.put("enable.idempotence", true);
        config.put("max.block.ms", sendTimeout.toMillis());
        config.put("delivery.timeout.ms", (int) sendTimeout.toMillis());
        config.put("request.timeout.ms", (int) Math.min(10000, sendTimeout.toMillis()));
        config.put("linger.ms", 0);
        config.put("key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        config.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        config.putAll(securityConfiguration());
        return config;
    }

    public Map<String, Object> securityConfiguration() {
        var config = new LinkedHashMap<String, Object>();
        config.put("security.protocol", securityProtocol);
        put(config, "sasl.mechanism", saslMechanism);
        put(config, "sasl.jaas.config", saslJaasConfig);
        put(config, "ssl.truststore.location", tls.getTrustStore());
        put(config, "ssl.truststore.password", tls.getTrustStorePassword());
        put(config, "ssl.truststore.type", tls.getTrustStoreType());
        put(config, "ssl.keystore.location", tls.getKeyStore());
        put(config, "ssl.keystore.password", tls.getKeyStorePassword());
        put(config, "ssl.keystore.type", tls.getKeyStoreType());
        put(config, "ssl.key.password", tls.getKeyPassword());
        return config;
    }

    private static void put(Map<String, Object> values, String name, String value) {
        if (value != null && !value.isBlank()) values.put(name, value);
    }

    @Override public String toString() { return "KafkaTransportProperties[enabled=" + enabled + ", secrets=<redacted>]"; }
}
