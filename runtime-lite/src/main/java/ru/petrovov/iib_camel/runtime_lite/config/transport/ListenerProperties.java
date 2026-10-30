package ru.petrovov.iib_camel.runtime_lite.config.transport;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("runtime-lite.listeners")
public class ListenerProperties {

    private boolean autoStartup = true;

    @Min(1)
    private int maxBodyBytes = 10 * 1024 * 1024;
}
