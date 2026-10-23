package ru.petrovov.iib_camel.runtime.config;

import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "camel.main.routes-include-pattern=classpath:delivery-contract/empty.xml")
@ActiveProfiles("artemis")
@EnabledIfEnvironmentVariable(named = "ARTEMIS_INTEGRATION", matches = "true")
class ArtemisDeliveryIntegrationTest extends JmsDeliveryContract {
    @Autowired
    private ConnectionFactory factory;

    @Override
    protected ConnectionFactory createConnectionFactory() {
        return factory;
    }

    @Override
    protected void closeBroker() { }
}
