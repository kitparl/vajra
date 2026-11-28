package com.vajra.gatewaybff.upstream;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Read-only view of core-service. */
@Component
public class CoreClient {

    private final UpstreamCall call;

    CoreClient(UpstreamProperties properties) {
        this.call = new UpstreamCall("core", properties.coreBaseUrl(), properties.timeout(), headers -> {});
    }

    public JsonNode instruments() {
        return get("/api/v1/instruments", Map.of());
    }

    public JsonNode quotes() {
        return get("/api/v1/quotes", Map.of());
    }

    public JsonNode positions() {
        return get("/api/v1/positions", Map.of());
    }

    public JsonNode pnl() {
        return get("/api/v1/pnl", Map.of());
    }

    public JsonNode orders(String state) {
        return get("/api/v1/orders", state == null ? Map.of() : Map.of("state", state));
    }

    public JsonNode order(String id) {
        return call.get("/api/v1/orders/{id}", Map.of("id", id), Map.of());
    }

    public JsonNode candles(String symbol, String from, String to) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("symbol", symbol);
        query.put("from", from);
        query.put("to", to);
        return get("/api/v1/candles", query);
    }

    public JsonNode status() {
        return get("/api/v1/status", Map.of());
    }

    /** The mode is core's to report; if core cannot answer the caller still gets an explicit UNKNOWN. */
    public String tradingMode() {
        try {
            return status().path("tradingMode").asString("UNKNOWN");
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            return "UNKNOWN";
        }
    }

    private JsonNode get(String path, Map<String, String> query) {
        return call.get(path, Map.of(), query);
    }
}
