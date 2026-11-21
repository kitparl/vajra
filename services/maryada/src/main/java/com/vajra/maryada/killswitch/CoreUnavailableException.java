package com.vajra.maryada.killswitch;

/** core-service could not be reached or answered with an error. */
public class CoreUnavailableException extends RuntimeException {

    public CoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
