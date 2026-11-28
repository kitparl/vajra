package com.vajra.gatewaybff.upstream;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class UpstreamExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UpstreamExceptionHandler.class);

    @ExceptionHandler(UpstreamUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(UpstreamUnavailableException e) {
        log.warn("Upstream {} unavailable: {}", e.upstream(), rootMessage(e));
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "UPSTREAM_UNAVAILABLE");
        body.put("upstream", e.upstream());
        return ResponseEntity.status(503).body(body);
    }

    @ExceptionHandler(UpstreamRejectedException.class)
    ResponseEntity<String> rejected(UpstreamRejectedException e) {
        return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON).body(e.body());
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
