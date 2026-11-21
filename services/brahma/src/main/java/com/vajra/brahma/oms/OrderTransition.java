package com.vajra.brahma.oms;

import java.time.Instant;

/** One state change of an order; {@code from} is null for the creation entry. */
public record OrderTransition(OrderState from, OrderState to, String reason, Instant at) {}
