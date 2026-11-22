package com.vajra.coreservice.oms;

import com.vajra.coreservice.connector.TradingVenue;
import com.vajra.coreservice.connector.Venue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every second, asks each registered venue about its resting orders. Orders on the Delta venue are asked at most
 * every {@value #DELTA_MIN_GAP_SECONDS} s each (rate limit), tracked in memory per order id. One failing order never
 * stops the rest.
 */
@Component
public class OpenOrderChecker {

    static final long DELTA_MIN_GAP_SECONDS = 2;
    private static final Logger log = LoggerFactory.getLogger(OpenOrderChecker.class);

    private final List<TradingVenue> venues;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final Clock clock;
    private final Map<UUID, Instant> lastChecked = new ConcurrentHashMap<>();

    public OpenOrderChecker(List<TradingVenue> venues, OrderRepository orders, OrderService orderService, Clock clock) {
        this.venues = List.copyOf(venues);
        this.orders = orders;
        this.orderService = orderService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1S")
    public void run() {
        Set<UUID> seen = new HashSet<>();
        for (TradingVenue venue : venues) {
            for (Order order : orders.findCheckable(venue.venue())) {
                seen.add(order.id());
                if (isDue(venue.venue(), order)) {
                    check(order);
                }
            }
        }
        lastChecked.keySet().retainAll(seen);
    }

    private boolean isDue(Venue venue, Order order) {
        if (venue != Venue.DELTA_TESTNET) {
            return true;
        }
        Instant now = clock.instant();
        Instant last = lastChecked.get(order.id());
        if (last != null && Duration.between(last, now).compareTo(Duration.ofSeconds(DELTA_MIN_GAP_SECONDS)) < 0) {
            return false;
        }
        lastChecked.put(order.id(), now);
        return true;
    }

    private void check(Order order) {
        try {
            orderService.onRestingCheck(order);
        } catch (RuntimeException e) {
            log.warn("Open-order check failed for order {}", order.id(), e);
        }
    }
}
