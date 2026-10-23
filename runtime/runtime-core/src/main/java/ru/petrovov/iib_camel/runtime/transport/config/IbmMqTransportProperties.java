package ru.petrovov.iib_camel.runtime.transport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("iib.transports.ibmmq")
public final class IbmMqTransportProperties extends JmsTransportProperties {
    private String host;
    private int port = 1414;
    private String queueManager;
    private String channel;
    private String cipherSuite;
    private String sslPeerName;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getQueueManager() { return queueManager; }
    public void setQueueManager(String queueManager) { this.queueManager = queueManager; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getCipherSuite() { return cipherSuite; }
    public void setCipherSuite(String cipherSuite) { this.cipherSuite = cipherSuite; }
    public String getSslPeerName() { return sslPeerName; }
    public void setSslPeerName(String sslPeerName) { this.sslPeerName = sslPeerName; }

    @Override public void validate() {
        super.validate();
        required(host, "iib.transports.ibmmq.host");
        required(queueManager, "iib.transports.ibmmq.queue-manager");
        required(channel, "iib.transports.ibmmq.channel");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("IBM MQ port must be in 1..65535");
        if (getTls().isEnabled()) required(cipherSuite, "iib.transports.ibmmq.cipher-suite");
        else if (cipherSuite != null || sslPeerName != null) throw new IllegalArgumentException("Enable TLS before configuring IBM MQ cipher or peer");
    }
}
