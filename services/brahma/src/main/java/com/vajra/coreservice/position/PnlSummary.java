package com.vajra.coreservice.position;

import java.math.BigDecimal;

/** Totals over all positions. {@code unrealizedPnl} and {@code netPnl} are null if a non-flat position has no mark. */
public record PnlSummary(BigDecimal realizedPnl, BigDecimal feesPaid, BigDecimal unrealizedPnl, BigDecimal netPnl) {}
