package ru.petrovov.iib_camel.runtime_lite.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@Validated
@ConfigurationProperties("runtime-lite.enrichment")
public class EnrichmentProperties {

    private static final Duration MIN_DURATION = Duration.ofMillis(1);

    @NotNull
    private Duration defaultReplyTimeout = Duration.ofSeconds(20);

    @NotNull
    private Duration dispatchTimeout = Duration.ofMinutes(2);

    @NotNull
    private Duration pollInterval = Duration.ofSeconds(1);

    @Min(1)
    private int batchSize = 20;

    @Min(1)
    private int replyConcurrency = 2;

    @NotNull
    private Map<@NotBlank String, @NotNull @Valid Operation> operations = new LinkedHashMap<>();

    @AssertTrue(message = "enrichment durations must be at least one millisecond")
    public boolean isDurationValid() {
        return atLeastOneMillisecond(defaultReplyTimeout)
            && atLeastOneMillisecond(dispatchTimeout)
            && atLeastOneMillisecond(pollInterval);
    }

    private static boolean atLeastOneMillisecond(Duration duration) {
        return duration != null && duration.compareTo(MIN_DURATION) >= 0;
    }

    @Getter
    @Setter
    public static class Operation {

        @NotBlank
        private String requestQueue;

        @NotBlank
        private String replyQueue;

        private Duration replyTimeout;

        @AssertTrue(message = "reply timeout must be at least one millisecond")
        public boolean isReplyTimeoutValid() {
            return replyTimeout == null || atLeastOneMillisecond(replyTimeout);
        }
    }
}
