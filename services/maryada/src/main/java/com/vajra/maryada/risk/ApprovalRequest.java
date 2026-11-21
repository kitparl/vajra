package com.vajra.maryada.risk;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Order approval request from core-service. {@code side}: BUY/SELL; {@code source}: MANUAL/SIGNAL/KILL_SWITCH. */
public record ApprovalRequest(
        @NotBlank @Size(max = 32) String clientOrderId,
        @NotBlank String venue,
        @NotBlank String symbol,
        @NotNull String side,
        @Min(1) long size,
        @NotBlank String orderType,
        BigDecimal limitPrice,
        boolean reduceOnly,
        @NotBlank String source,
        long currentPosition) {}
