package ru.petrovov.iib_camel.runtime_lite.enrichment;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "runtime-lite.enrichment", name = "scheduling-enabled", matchIfMissing = true)
class EnrichmentSchedule {

    private static final String SCHEDULER = "enrichmentTaskScheduler";
    private static final String POLL_INTERVAL = "${runtime-lite.enrichment.poll-interval:PT1S}";

    private final EnrichmentCatalog catalog;
    private final EnrichmentWorker worker;

    @Bean(name = SCHEDULER, defaultCandidate = false)
    static ThreadPoolTaskScheduler enrichmentTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("enrichment-");
        return scheduler;
    }

    @Scheduled(fixedDelayString = POLL_INTERVAL, scheduler = SCHEDULER)
    void resume() {
        if (!catalog.replyQueues().isEmpty()) {
            worker.resumeBatch();
        }
    }

    @Scheduled(fixedDelayString = POLL_INTERVAL, scheduler = SCHEDULER)
    void expire() {
        if (!catalog.replyQueues().isEmpty()) {
            worker.expireBatch();
        }
    }
}
