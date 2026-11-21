package com.vajra.coreservice.oms;

/** Outcome of a kill: orders cancelled, flatten orders placed, and whether every position is now flat. */
public record KillResult(int cancelledOrders, int flattenOrders, boolean flattened) {}
