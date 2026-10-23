package ru.petrovov.iib_camel.runtime.config;

import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

class JmsDeliveryPolicyTest extends JmsDeliveryContract {
    private EmbeddedActiveMQ broker;
    private ActiveMQConnectionFactory factory;

    @Override
    protected ConnectionFactory createConnectionFactory() throws Exception {
        var config = new ConfigurationImpl().setPersistenceEnabled(false).setSecurityEnabled(false)
                .setJMXManagementEnabled(false).addAcceptorConfiguration("in-vm", "vm://42");
        config.addAddressSetting("#", new AddressSettings().setMaxDeliveryAttempts(-1));
        broker = new EmbeddedActiveMQ().setConfiguration(config);
        broker.start();
        factory = new ActiveMQConnectionFactory("vm://42");
        return factory;
    }

    @Override
    protected void closeBroker() throws Exception {
        if (factory != null) factory.close();
        if (broker != null) broker.stop();
    }
}
