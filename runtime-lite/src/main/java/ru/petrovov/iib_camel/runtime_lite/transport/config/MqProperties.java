package ru.petrovov.iib_camel.runtime_lite.transport.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("runtime-lite.mq")
public class MqProperties {

    private boolean enabled = true;

    @NotBlank
    private String host = "localhost";

    @Min(1)
    @Max(65535)
    private int port = 1414;

    @NotBlank
    private String queueManager = "QM1";

    @NotBlank
    private String channel = "APP.SVRCONN";

    private String username;
    private String password;
    private String cipherSuite;
    private String peerName;

    @AssertTrue(message = "MQ username and password must be configured together")
    public boolean isCredentialsConsistent() {
        boolean hasUsername = username != null && !username.isBlank();
        boolean hasPassword = password != null && !password.isEmpty();
        return hasUsername == hasPassword;
    }

    @AssertTrue(message = "MQ peer name requires a cipher suite")
    public boolean isTlsConsistent() {
        return peerName == null || peerName.isBlank() || cipherSuite != null && !cipherSuite.isBlank();
    }
}
