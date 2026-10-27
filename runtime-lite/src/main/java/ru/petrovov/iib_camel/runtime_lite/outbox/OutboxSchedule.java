package ru.petrovov.iib_camel.runtime_lite.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "runtime-lite.outbox", name = "scheduling-enabled", matchIfMissing = true)
class OutboxSchedule {

    private final OutboxDispatcher dispatcher;

    @Scheduled(fixedDelayString = "${runtime-lite.outbox.poll-interval:PT1S}")
    void dispatch() {
        dispatcher.dispatchBatch();
    }
}
