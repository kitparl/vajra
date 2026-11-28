package com.vajra.gatewaybff.upstream;

import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Read-only view of risk-service; every call carries the shared bearer token. */
@Component
public class RiskClient {

    private final UpstreamCall call;

    RiskClient(UpstreamProperties properties) {
        this.call = new UpstreamCall("risk", properties.riskBaseUrl(), properties.timeout(), headers -> headers.setBearerAuth(properties.riskApiToken()));
    }

    public JsonNode killSwitch() {
        return call.get("/api/v1/kill-switch", Map.of(), Map.of());
    }

    public JsonNode limits() {
        return call.get("/api/v1/limits", Map.of(), Map.of());
    }

    public JsonNode rejections(String limit) {
        return call.get("/api/v1/rejections", Map.of(), limit == null ? Map.of() : Map.of("limit", limit));
    }
}
