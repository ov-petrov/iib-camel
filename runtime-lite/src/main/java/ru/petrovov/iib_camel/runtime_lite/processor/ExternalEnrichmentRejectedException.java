package ru.petrovov.iib_camel.runtime_lite.processor;

public class ExternalEnrichmentRejectedException extends RuntimeException {

    public ExternalEnrichmentRejectedException(String message) {
        super(message);
    }

    public ExternalEnrichmentRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
