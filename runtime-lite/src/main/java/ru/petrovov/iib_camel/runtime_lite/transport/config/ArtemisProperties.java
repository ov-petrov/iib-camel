package ru.petrovov.iib_camel.runtime_lite.transport.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("runtime-lite.artemis")
public class ArtemisProperties {

    @NotBlank
    private String brokerUrl = "tcp://localhost:61616";

    private String username;
    private String password;

    @AssertTrue(message = "Artemis username and password must be configured together")
    public boolean isCredentialsConsistent() {
        boolean hasUsername = username != null && !username.isBlank();
        boolean hasPassword = password != null && !password.isEmpty();
        return hasUsername == hasPassword;
    }
}
