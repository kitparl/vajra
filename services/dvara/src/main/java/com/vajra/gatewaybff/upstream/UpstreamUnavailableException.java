package com.vajra.gatewaybff.upstream;

/** Connection failure, timeout or upstream 5xx: the gateway answers 503 {@code UPSTREAM_UNAVAILABLE}. */
public class UpstreamUnavailableException extends RuntimeException {

    private final String upstream;

    public UpstreamUnavailableException(String upstream, Throwable cause) {
        super("upstream " + upstream + " unavailable", cause);
        this.upstream = upstream;
    }

    public String upstream() {
        return upstream;
    }
}
