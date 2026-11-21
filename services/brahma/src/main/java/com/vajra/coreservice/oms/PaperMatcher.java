package com.vajra.coreservice.oms;

import com.vajra.coreservice.connector.Venue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every second, checks resting paper orders against the latest quotes. One failing order never stops the rest. */
@Component
public class PaperMatcher {

    private static final Logger log = LoggerFactory.getLogger(PaperMatcher.class);

    private final OrderRepository orders;
    private final OrderService orderService;

    public PaperMatcher(OrderRepository orders, OrderService orderService) {
        this.orders = orders;
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "PT1S")
    public void run() {
        for (Order order : orders.findOpen(Venue.PAPER)) {
            try {
                orderService.onRestingCheck(order);
            } catch (RuntimeException e) {
                log.warn("Paper matcher failed for order {}", order.id(), e);
            }
        }
    }
}
