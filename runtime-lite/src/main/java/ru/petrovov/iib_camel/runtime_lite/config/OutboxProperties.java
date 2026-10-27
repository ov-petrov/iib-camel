package ru.petrovov.iib_camel.runtime_lite.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties("runtime-lite.outbox")
public class OutboxProperties {

    @NotNull
    @Pattern(regexp = "[a-z_][a-z0-9_]{0,62}")
    private String schema = "runtime_lite";

    @Min(1)
    private int batchSize = 20;

    @Min(1)
    private int maxAttempts = 10;

    @NotNull
    private Duration retryDelay = Duration.ofSeconds(10);

    @NotNull
    private Duration pollInterval = Duration.ofSeconds(1);

    public void validateDurations() {
        if (retryDelay.toMillis() < 1 || pollInterval.toMillis() < 1) {
            throw new IllegalArgumentException("Outbox durations must be at least one millisecond");
        }
    }
}
