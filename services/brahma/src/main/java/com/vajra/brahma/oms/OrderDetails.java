package com.vajra.brahma.oms;

import java.util.List;

/** An order with its full history. */
public record OrderDetails(Order order, List<OrderTransition> transitions, List<Fill> fills) {}
