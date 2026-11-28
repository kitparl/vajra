package com.vajra.gatewaybff.api;

import com.vajra.gatewaybff.upstream.CoreClient;
import com.vajra.gatewaybff.upstream.RiskClient;
import java.util.List;
import java.util.stream.StreamSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only passthrough of core and risk data for the terminal UI. Bodies stay JSON nodes. */
@RestController
class ReadController {

    private final CoreClient core;
    private final RiskClient risk;
    private final JsonMapper mapper;

    ReadController(CoreClient core, RiskClient risk, JsonMapper mapper) {
        this.core = core;
        this.risk = risk;
        this.mapper = mapper;
    }

    @GetMapping("/api/v1/instruments")
    JsonNode instruments() {
        List<JsonNode> enabled = StreamSupport.stream(core.instruments().spliterator(), false)
                .filter(instrument -> instrument.path("tradingEnabled").asBoolean(false))
                .toList();
        return mapper.createArrayNode().addAll(enabled);
    }

    @GetMapping("/api/v1/quotes")
    JsonNode quotes() {
        return core.quotes();
    }

    @GetMapping("/api/v1/positions")
    JsonNode positions() {
        return core.positions();
    }

    @GetMapping("/api/v1/pnl")
    JsonNode pnl() {
        return core.pnl();
    }

    @GetMapping("/api/v1/orders")
    JsonNode orders(@RequestParam(required = false) String state) {
        return core.orders(state);
    }

    @GetMapping("/api/v1/orders/{id}")
    JsonNode order(@PathVariable String id) {
        return core.order(id);
    }

    @GetMapping("/api/v1/candles")
    JsonNode candles(@RequestParam String symbol, @RequestParam String from, @RequestParam String to) {
        return core.candles(symbol, from, to);
    }

    @GetMapping("/api/v1/risk/status")
    JsonNode riskStatus() {
        return risk.killSwitch();
    }

    @GetMapping("/api/v1/risk/limits")
    JsonNode riskLimits() {
        return risk.limits();
    }

    @GetMapping("/api/v1/risk/rejections")
    JsonNode riskRejections(@RequestParam(required = false) String limit) {
        return risk.rejections(limit);
    }
}
