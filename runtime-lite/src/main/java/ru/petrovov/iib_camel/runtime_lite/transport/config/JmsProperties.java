package ru.petrovov.iib_camel.runtime_lite.transport.config;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("runtime-lite.jms")
public class JmsProperties {

    @NotNull
    private Provider provider = Provider.IBM_MQ;

    public enum Provider {
        IBM_MQ,
        ARTEMIS
    }
}
