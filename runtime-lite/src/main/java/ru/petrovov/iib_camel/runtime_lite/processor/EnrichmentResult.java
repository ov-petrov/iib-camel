package ru.petrovov.iib_camel.runtime_lite.processor;

import java.util.Objects;

public sealed interface EnrichmentResult<R> {

    record Skipped<R>() implements EnrichmentResult<R> {
    }

    record Success<R>(R reply) implements EnrichmentResult<R> {

        public Success {
            Objects.requireNonNull(reply, "enrichment reply");
        }
    }

    record Failure<R>(EnrichmentFailure reason) implements EnrichmentResult<R> {

        public Failure {
            Objects.requireNonNull(reason, "enrichment failure reason");
        }
    }
}
