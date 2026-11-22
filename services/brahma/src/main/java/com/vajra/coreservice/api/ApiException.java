package com.vajra.coreservice.api;

import org.springframework.http.HttpStatus;

/** A client error with the HTTP status it maps to. Thrown by services, rendered by {@link ApiExceptionHandler}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final boolean retryable;

    private ApiException(HttpStatus status, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.retryable = retryable;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, false);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message, false);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message, false);
    }

    /** A 409 caused by a concurrent change: the same request can succeed when retried. */
    public static ApiException retryableConflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message, true);
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }
}
