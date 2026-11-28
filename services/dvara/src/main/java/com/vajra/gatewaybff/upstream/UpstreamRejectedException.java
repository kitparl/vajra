package com.vajra.gatewaybff.upstream;

import org.springframework.http.HttpStatusCode;

/** Upstream 4xx: returned to the caller with the same status and body. */
public class UpstreamRejectedException extends RuntimeException {

    private final HttpStatusCode status;
    private final String body;

    public UpstreamRejectedException(HttpStatusCode status, String body) {
        super("upstream rejected with " + status.value());
        this.status = status;
        this.body = body;
    }

    public HttpStatusCode status() {
        return status;
    }

    public String body() {
        return body;
    }
}
