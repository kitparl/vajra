package com.vajra.coreservice.connector.delta;

/**
 * A Delta call failed: network error, non-2xx answer, or a local rate-limit pause. The message never holds a header
 * value or a request body, so it is safe to log.
 */
public class DeltaApiException extends RuntimeException {

    private final boolean rateLimited;
    private final String code;
    private final boolean retryable;
    private final int status;

    public DeltaApiException(String message, boolean rateLimited, Throwable cause) {
        this(message, rateLimited, rateLimited ? "RATE_LIMITED" : "DELTA_ERROR", false, 0, cause);
    }

    /**
     * @param code Delta error code, or {@code HTTP_<status>} when the body has none
     * @param retryable 5xx, I/O error or timeout: the outcome at Delta is unknown
     * @param status HTTP status, 0 when there was no answer
     */
    public DeltaApiException(String message, boolean rateLimited, String code, boolean retryable, int status, Throwable cause) {
        super(message, cause);
        this.rateLimited = rateLimited;
        this.code = code;
        this.retryable = retryable;
        this.status = status;
    }

    public boolean rateLimited() {
        return rateLimited;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public int status() {
        return status;
    }
}
