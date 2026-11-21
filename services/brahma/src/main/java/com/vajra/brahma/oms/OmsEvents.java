package com.vajra.brahma.oms;

import com.google.protobuf.Timestamp;
import com.vajra.brahma.position.Position;
import com.vajra.contracts.events.v1.FillCreated;
import com.vajra.contracts.events.v1.OrderUpdated;
import com.vajra.contracts.events.v1.PositionUpdated;
import java.math.BigDecimal;
import java.time.Instant;

/** Maps OMS records to their audit event payloads. Decimals are plain strings; a missing value is "". */
final class OmsEvents {

    static final String ORDER_UPDATED = "order.updated";
    static final String FILL_CREATED = "fill.created";
    static final String POSITION_UPDATED = "position.updated";

    private OmsEvents() {}

    static OrderUpdated orderUpdated(Order o) {
        return OrderUpdated.newBuilder()
                .setOrderId(o.id().toString())
                .setClientOrderId(o.clientOrderId())
                .setVenue(o.venue().name())
                .setSymbol(o.symbol())
                .setSide(o.side().name())
                .setOrderType(o.type().name())
                .setSize(o.size())
                .setLimitPrice(decimal(o.limitPrice()))
                .setTimeInForce(o.timeInForce().name())
                .setReduceOnly(o.reduceOnly())
                .setState(o.state().name())
                .setFilledSize(o.filledSize())
                .setAvgFillPrice(decimal(o.avgFillPrice()))
                .setFees(decimal(o.fees()))
                .setReason(o.reason() == null ? "" : o.reason())
                .setSource(o.source().name())
                .setUpdatedAt(timestamp(o.updatedAt()))
                .build();
    }

    static FillCreated fillCreated(Fill f, Order o) {
        return FillCreated.newBuilder()
                .setFillId(f.id().toString())
                .setOrderId(o.id().toString())
                .setVenue(f.venue().name())
                .setSymbol(o.symbol())
                .setSide(o.side().name())
                .setPrice(decimal(f.price()))
                .setSize(f.size())
                .setFee(decimal(f.fee()))
                .setLiquidity(f.liquidity() == null ? "" : f.liquidity())
                .setFilledAt(timestamp(f.at()))
                .build();
    }

    static PositionUpdated positionUpdated(Position p) {
        return PositionUpdated.newBuilder()
                .setVenue(p.venue().name())
                .setSymbol(p.symbol())
                .setSize(p.size())
                .setAvgEntryPrice(decimal(p.avgEntryPrice()))
                .setRealizedPnl(decimal(p.realizedPnl()))
                .setFeesPaid(decimal(p.feesPaid()))
                .setUpdatedAt(timestamp(p.updatedAt()))
                .build();
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static Timestamp timestamp(Instant at) {
        return Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()).build();
    }
}
