package ru.petrovov.iib_camel.runtime.config;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import org.junit.jupiter.api.Test;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jms.connection.UserCredentialsConnectionFactoryAdapter;

import static org.assertj.core.api.Assertions.assertThat;

class IbmMqConfigTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(IbmMqConfig.class)
            .withPropertyValues("spring.profiles.active=ibm-mq");

    @Test
    void createsActualJakartaClientWithBoundPoolAndTlsSettingsWithoutConnecting() {
        context.withPropertyValues("iib.mq.host=mq.example.test", "iib.mq.port=1415",
                "iib.mq.queue-manager=QM1", "iib.mq.channel=APP.SVRCONN",
                "iib.mq.user=app", "iib.mq.password=secret", "iib.mq.max-connections=3",
                "iib.mq.max-sessions-per-connection=7", "iib.mq.pool-wait-timeout=2500",
                "iib.mq.ssl-cipher-suite=TLS_AES_256_GCM_SHA384", "iib.mq.ssl-peer-name=CN=mq")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    var pool = application.getBean(JmsPoolConnectionFactory.class);
                    assertThat(pool.getNumConnections()).isZero();
                    assertThat(pool.getMaxConnections()).isEqualTo(3);
                    assertThat(pool.getMaxSessionsPerConnection()).isEqualTo(7);
                    assertThat(pool.getBlockIfSessionPoolIsFullTimeout()).isEqualTo(2500);
                    assertThat(pool.getConnectionFactory()).isInstanceOf(UserCredentialsConnectionFactoryAdapter.class);
                    var factory = new IbmMqConfig().mqConnectionFactory(application.getBean(IbmMqProperties.class));
                    assertThat(factory.getHostName()).isEqualTo("mq.example.test");
                    assertThat(factory.getPort()).isEqualTo(1415);
                    assertThat(factory.getQueueManager()).isEqualTo("QM1");
                    assertThat(factory.getChannel()).isEqualTo("APP.SVRCONN");
                    assertThat(factory.getTransportType()).isEqualTo(WMQConstants.WMQ_CM_CLIENT);
                    assertThat(factory.getSSLCipherSuite()).isEqualTo("TLS_AES_256_GCM_SHA384");
                    assertThat(factory.getSSLPeerName()).isEqualTo("CN=mq");
                    assertThat(application.getBean(IbmMqProperties.class).toString()).doesNotContain("secret");
                });
    }

    @Test
    void ibmProfileFailsAtStartupWhenRequiredSettingsAreMissing() {
        context.run(application -> assertThat(application).hasFailed());
    }

    @Test
    void rejectsInvalidPoolAndPortBeforeAnyConnectionAttempt() {
        context.withPropertyValues("iib.mq.host=localhost", "iib.mq.queue-manager=QM1",
                "iib.mq.channel=APP.SVRCONN", "iib.mq.port=0", "iib.mq.max-connections=0")
                .run(application -> assertThat(application).hasFailed());
    }
}
