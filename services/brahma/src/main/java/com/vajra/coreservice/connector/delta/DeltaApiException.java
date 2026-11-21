package com.vajra.coreservice.connector.delta;

/** A Delta call failed: network error, non-2xx answer, or a local rate-limit pause. */
public class DeltaApiException extends RuntimeException {

    private final boolean rateLimited;

    public DeltaApiException(String message, boolean rateLimited, Throwable cause) {
        super(message, cause);
        this.rateLimited = rateLimited;
    }

    public boolean rateLimited() {
        return rateLimited;
    }
}
