package ru.petrovov.iib_camel.runtime.config;

import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;

@EnabledIfEnvironmentVariable(named = "IBM_MQ_INTEGRATION", matches = "true")
class IbmMqDeliveryIntegrationTest extends JmsDeliveryContract {
    private JmsPoolConnectionFactory factory;

    @Override
    protected ConnectionFactory createConnectionFactory() throws Exception {
        var properties = new IbmMqProperties(required("IBM_MQ_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("IBM_MQ_PORT", "21414")),
                required("IBM_MQ_QUEUE_MANAGER"), required("IBM_MQ_CHANNEL"),
                required("IBM_MQ_USER"), required("IBM_MQ_PASSWORD"),
                System.getenv("IBM_MQ_SSL_CIPHER_SUITE"), System.getenv("IBM_MQ_SSL_PEER_NAME"),
                2, 20, 5000);
        factory = new IbmMqConfig().connectionFactory(properties);
        return factory;
    }

    @Override
    protected void closeBroker() {
        if (factory != null) factory.stop();
    }

    private String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}
