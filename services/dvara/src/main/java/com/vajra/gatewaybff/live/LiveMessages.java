package com.vajra.gatewaybff.live;

import com.google.protobuf.Timestamp;
import com.vajra.contracts.events.v1.FillCreated;
import com.vajra.contracts.events.v1.OrderUpdated;
import com.vajra.contracts.events.v1.PositionUpdated;
import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON the terminal receives: {@code {"type":..., "data":...}}. Protobuf decimals are already strings and are
 * copied as they are; they never pass through a floating point type.
 */
@Component
class LiveMessages {

    private final JsonMapper mapper;

    LiveMessages(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String hello(String tradingMode) {
        return envelope("hello", mapper.createObjectNode().put("tradingMode", tradingMode));
    }

    String quotes(JsonNode quotes) {
        return envelope("quote", quotes);
    }

    String risk(JsonNode killSwitch) {
        ObjectNode data = mapper.createObjectNode();
        data.set("halted", killSwitch.path("halted").isMissingNode() ? null : killSwitch.get("halted"));
        data.set("events", killSwitch.path("events").isMissingNode() ? null : killSwitch.get("events"));
        return envelope("risk", data);
    }

    String upstream(String upstream, boolean available) {
        return envelope("upstream", mapper.createObjectNode().put("upstream", upstream).put("available", available));
    }

    String order(OrderUpdated o) {
        ObjectNode data = mapper.createObjectNode()
                .put("orderId", o.getOrderId())
                .put("clientOrderId", o.getClientOrderId())
                .put("venue", o.getVenue())
                .put("symbol", o.getSymbol())
                .put("side", o.getSide())
                .put("orderType", o.getOrderType())
                .put("size", o.getSize())
                .put("limitPrice", o.getLimitPrice())
                .put("timeInForce", o.getTimeInForce())
                .put("reduceOnly", o.getReduceOnly())
                .put("state", o.getState())
                .put("filledSize", o.getFilledSize())
                .put("avgFillPrice", o.getAvgFillPrice())
                .put("fees", o.getFees())
                .put("reason", o.getReason())
                .put("source", o.getSource())
                .put("updatedAt", iso(o.hasUpdatedAt(), o.getUpdatedAt()));
        return envelope("order", data);
    }

    String fill(FillCreated f) {
        ObjectNode data = mapper.createObjectNode()
                .put("fillId", f.getFillId())
                .put("orderId", f.getOrderId())
                .put("venue", f.getVenue())
                .put("symbol", f.getSymbol())
                .put("side", f.getSide())
                .put("price", f.getPrice())
                .put("size", f.getSize())
                .put("fee", f.getFee())
                .put("liquidity", f.getLiquidity())
                .put("filledAt", iso(f.hasFilledAt(), f.getFilledAt()));
        return envelope("fill", data);
    }

    String position(PositionUpdated p) {
        ObjectNode data = mapper.createObjectNode()
                .put("venue", p.getVenue())
                .put("symbol", p.getSymbol())
                .put("size", p.getSize())
                .put("avgEntryPrice", p.getAvgEntryPrice())
                .put("realizedPnl", p.getRealizedPnl())
                .put("feesPaid", p.getFeesPaid())
                .put("updatedAt", iso(p.hasUpdatedAt(), p.getUpdatedAt()));
        return envelope("position", data);
    }

    private String envelope(String type, JsonNode data) {
        ObjectNode message = mapper.createObjectNode();
        message.put("type", type);
        message.set("data", data);
        return mapper.writeValueAsString(message);
    }

    private static String iso(boolean present, Timestamp timestamp) {
        return present ? Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()).toString() : null;
    }
}
