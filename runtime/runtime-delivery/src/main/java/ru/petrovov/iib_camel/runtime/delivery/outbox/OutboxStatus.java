package ru.petrovov.iib_camel.runtime.delivery.outbox;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OutboxStatus {
    READY("READY"),
    IN_PROGRESS("IN_PROGRESS"),
    PROCESSED("PROCESSED"),
    FAILED("FAILED");


    private final String databaseValue;
}
