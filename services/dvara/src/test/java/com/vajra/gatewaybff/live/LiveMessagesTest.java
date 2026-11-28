package com.vajra.gatewaybff.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import com.vajra.contracts.events.v1.FillCreated;
import com.vajra.contracts.events.v1.PositionUpdated;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LiveMessagesTest {

    private final LiveMessages messages = new LiveMessages(JsonMapper.builder().build());

    @Test
    void fillKeepsDecimalsAsStrings() {
        String json = messages.fill(FillCreated.newBuilder().setFillId("f-1").setOrderId("o-1").setSymbol("BTCUSD")
                .setPrice("100000.10").setSize(2).setFee("0.0100")
                .setFilledAt(Timestamp.newBuilder().setSeconds(1_700_000_000L)).build());

        assertThat(json).startsWith("{\"type\":\"fill\"").contains("\"price\":\"100000.10\"", "\"fee\":\"0.0100\"",
                "\"size\":2", "\"fillId\":\"f-1\"", "\"filledAt\":\"2023-11-14T22:13:20Z\"");
    }

    @Test
    void positionKeepsDecimalsAsStrings() {
        String json = messages.position(PositionUpdated.newBuilder().setSymbol("BTCUSD").setSize(-3)
                .setAvgEntryPrice("99999.50").setRealizedPnl("-12.3400").setFeesPaid("0.0500").build());

        assertThat(json).startsWith("{\"type\":\"position\"").contains("\"avgEntryPrice\":\"99999.50\"",
                "\"realizedPnl\":\"-12.3400\"", "\"feesPaid\":\"0.0500\"", "\"size\":-3", "\"updatedAt\":null");
    }
}
