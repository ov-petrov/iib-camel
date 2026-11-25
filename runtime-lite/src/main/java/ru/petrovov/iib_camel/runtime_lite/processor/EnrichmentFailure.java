package ru.petrovov.iib_camel.runtime_lite.processor;

public enum EnrichmentFailure {
    DISPATCH_TIMEOUT,
    DISPATCH_FAILED,
    REPLY_TIMEOUT,
    INVALID_REPLY,
    EXTERNAL_REJECTED,
    MAPPING_FAILED
}
