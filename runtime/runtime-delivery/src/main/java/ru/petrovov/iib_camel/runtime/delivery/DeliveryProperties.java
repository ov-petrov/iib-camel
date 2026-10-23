package ru.petrovov.iib_camel.runtime.delivery;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("iib.delivery")
public class DeliveryProperties {
    private boolean enabled;
    private boolean publisherEnabled;
    private int batchSize = 20;
    private int maxAttempts = 5;
    private Duration lease = Duration.ofSeconds(60);
    private Duration backoff = Duration.ofSeconds(1);
    private final Scheduler scheduler = new Scheduler();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isPublisherEnabled() { return publisherEnabled; }
    public void setPublisherEnabled(boolean publisherEnabled) { this.publisherEnabled = publisherEnabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Duration getLease() { return lease; }
    public void setLease(Duration lease) { this.lease = lease; }
    public Duration getBackoff() { return backoff; }
    public void setBackoff(Duration backoff) { this.backoff = backoff; }
    public Scheduler getScheduler() { return scheduler; }

    void validate() {
        if (batchSize < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("Delivery batch size and max attempts must be positive");
        }
        positiveMillis(lease, "lease");
        positiveMillis(backoff, "backoff");
    }

    private static void positiveMillis(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative() || duration.toMillis() < 1) {
            throw new IllegalArgumentException("Delivery " + name + " must be at least one millisecond");
        }
    }

    long backoffMillis(int attempt) {
        long initial = backoff.toMillis();
        int exponent = Math.min(Math.max(attempt - 1, 0), 30);
        long multiplier = 1L << exponent;
        return initial > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : initial * multiplier;
    }

    public static class Scheduler {
        private Duration fixedDelay = Duration.ofSeconds(1);

        public Duration getFixedDelay() { return fixedDelay; }
        public void setFixedDelay(Duration fixedDelay) { this.fixedDelay = fixedDelay; }
    }
}
