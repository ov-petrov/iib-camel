package ru.petrovov.iib_camel.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("iib.mq")
public record IbmMqProperties(
        String host,
        @DefaultValue("1414") int port,
        String queueManager,
        String channel,
        String user,
        String password,
        String sslCipherSuite,
        String sslPeerName,
        @DefaultValue("2") int maxConnections,
        @DefaultValue("20") int maxSessionsPerConnection,
        @DefaultValue("5000") long poolWaitTimeout) {

    public void validate() {
        if (host == null || host.isBlank() || queueManager == null || queueManager.isBlank()
                || channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("IBM MQ profile requires iib.mq.host, queue-manager and channel");
        }
        if (port < 1 || port > 65535 || maxConnections < 1 || maxSessionsPerConnection < 1 || poolWaitTimeout < 0) {
            throw new IllegalArgumentException("Invalid IBM MQ port or pool limits");
        }
        if (password != null && !password.isEmpty() && (user == null || user.isBlank())) {
            throw new IllegalArgumentException("IBM MQ password requires a user");
        }
    }

    @Override
    public String toString() {
        return "IbmMqProperties[host=" + host + ", port=" + port + ", queueManager=" + queueManager
                + ", channel=" + channel + ", credentials=<redacted>]";
    }
}
