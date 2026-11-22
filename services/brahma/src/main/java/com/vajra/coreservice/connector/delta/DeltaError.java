package com.vajra.coreservice.connector.delta;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Error code parsed from a Delta error body. Delta uses {@code {"error":{"code":"..."}}}, but also
 * {@code {"error":"SignatureExpired"}} and plain text from the CDN, which fall back to {@code HTTP_<status>}.
 */
record DeltaError(String code) {

    static DeltaError parse(JsonMapper mapper, String body, int status) {
        String fallback = "HTTP_" + status;
        if (body == null || body.isBlank()) {
            return new DeltaError(fallback);
        }
        try {
            return new DeltaError(codeOf(mapper.readTree(body), fallback));
        } catch (RuntimeException e) {
            return new DeltaError(fallback);
        }
    }

    static String codeOf(JsonNode root, String fallback) {
        JsonNode error = root.path("error");
        if (error.isObject() && error.path("code").isString()) {
            return error.path("code").asString();
        }
        if (error.isString() && !error.asString().isBlank()) {
            return error.asString();
        }
        return fallback;
    }
}
