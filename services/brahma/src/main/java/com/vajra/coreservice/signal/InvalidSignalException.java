package com.vajra.coreservice.signal;

/** A signal message that can never become an order (unparseable or malformed); retrying will not help. */
public class InvalidSignalException extends RuntimeException {

    public InvalidSignalException(String message) {
        super(message);
    }

    public InvalidSignalException(String message, Throwable cause) {
        super(message, cause);
    }
}
