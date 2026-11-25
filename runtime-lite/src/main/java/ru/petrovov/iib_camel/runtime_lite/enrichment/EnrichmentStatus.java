package ru.petrovov.iib_camel.runtime_lite.enrichment;

enum EnrichmentStatus {
    PENDING_SEND,
    WAITING_REPLY,
    READY_TO_MAP,
    COMPLETED,
    FAILED
}
