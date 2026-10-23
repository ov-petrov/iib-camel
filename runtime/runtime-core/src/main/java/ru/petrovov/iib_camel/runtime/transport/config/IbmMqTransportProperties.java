package ru.petrovov.iib_camel.runtime.transport.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("iib.transports.ibmmq")
public final class IbmMqTransportProperties extends JmsTransportProperties {
    private String host;
    private int port = 1414;
    private String queueManager;
    private String channel;
    private String cipherSuite;
    private String sslPeerName;

    @Override
    public void validate() {
        super.validate();
        required(host, "iib.transports.ibmmq.host");
        required(queueManager, "iib.transports.ibmmq.queue-manager");
        required(channel, "iib.transports.ibmmq.channel");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("IBM MQ port must be in 1..65535");
        if (getTls().isEnabled()) required(cipherSuite, "iib.transports.ibmmq.cipher-suite");
        else if (cipherSuite != null || sslPeerName != null) {
            throw new IllegalArgumentException("Enable TLS before configuring IBM MQ cipher or peer");
        }
    }
}
