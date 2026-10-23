package ru.petrovov.iib_camel.runtime.delivery.inbox;

public final class ConflictingMessageException extends IllegalStateException {
    public ConflictingMessageException() {
        super("Inbound identity was already accepted with a different payload");
    }
}
