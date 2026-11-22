package com.vajra.coreservice.connector.delta;

import java.math.BigDecimal;

/** Delta position: {@code size} is signed (negative is short), 0 when flat. */
public record DeltaPosition(long size, BigDecimal entryPrice) {}
