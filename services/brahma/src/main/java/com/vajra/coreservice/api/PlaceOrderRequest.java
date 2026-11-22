package com.vajra.coreservice.api;

import com.vajra.coreservice.oms.OrderIntent;
import com.vajra.coreservice.oms.OrderSource;
import com.vajra.coreservice.oms.OrderType;
import com.vajra.coreservice.oms.Side;
import com.vajra.coreservice.oms.TimeInForce;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Body of {@code POST /api/v1/orders}. Field rules that need a lookup live in the order service. */
record PlaceOrderRequest(
        @NotBlank String symbol,
        @NotNull Side side,
        @NotNull OrderType type,
        @Min(1) long size,
        BigDecimal limitPrice,
        TimeInForce timeInForce,
        Boolean reduceOnly) {

    OrderIntent toIntent(String idempotencyKey) {
        return new OrderIntent(
                idempotencyKey, symbol, side, type, size, limitPrice, timeInForce, Boolean.TRUE.equals(reduceOnly), OrderSource.MANUAL, null);
    }
}
