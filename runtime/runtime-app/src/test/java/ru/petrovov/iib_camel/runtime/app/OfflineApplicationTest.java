package ru.petrovov.iib_camel.runtime.app;

import org.apache.camel.CamelContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.profiles.active=offline",
    "spring.main.web-application-type=none",
    "camel.main.run-controller=false"})
class OfflineApplicationTest {
    @Autowired
    CamelContext camel;

    @Test
    void noModuleOrTransportIsEnabledImplicitly() {
        assertTrue(camel.getRoutes().isEmpty());
        assertFalse(camel.hasComponent("artemis") != null);
        assertFalse(camel.hasComponent("ibmmq") != null);
    }
}
