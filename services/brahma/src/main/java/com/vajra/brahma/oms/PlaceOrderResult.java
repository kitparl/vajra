package com.vajra.brahma.oms;

/** The order after placement; {@code created} is false when an existing order was returned for the same key. */
public record PlaceOrderResult(Order order, boolean created) {}
