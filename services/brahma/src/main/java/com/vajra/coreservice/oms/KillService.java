package com.vajra.coreservice.oms;

import com.vajra.coreservice.connector.TradingVenue;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.position.Position;
import com.vajra.coreservice.position.PositionRepository;
import com.vajra.coreservice.risk.TradingStateRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The core side of the kill switch, called by risk-service: halt, cancel every open order, flatten every
 * position with reduce-only MARKET orders ({@code source = KILL_SWITCH}), each on the venue where the position sits.
 *
 * <p>Safe to call again: kill and resume run under one JVM lock (core-service is a single instance) and positions are
 * re-read on every kill, so a second or concurrent kill sees the flat position and places nothing. Each flatten gets a
 * fresh idempotency key, so a retry after a rejected flatten (e.g. stale quote) places a new order instead of
 * replaying the rejected one.
 */
@Service
public class KillService {

    private static final Logger log = LoggerFactory.getLogger(KillService.class);
    private static final String RESUMED = "resumed";

    private final ReentrantLock lock = new ReentrantLock();
    private final OrderService orderService;
    private final OrderRepository orders;
    private final PositionRepository positions;
    private final TradingStateRepository tradingState;
    private final List<TradingVenue> venues;
    private final Clock clock;

    KillService(OrderService orderService, OrderRepository orders, PositionRepository positions,
            TradingStateRepository tradingState, List<TradingVenue> venues, Clock clock) {
        this.orderService = orderService;
        this.orders = orders;
        this.positions = positions;
        this.tradingState = tradingState;
        this.venues = List.copyOf(venues);
        this.clock = clock;
    }

    /**
     * Per-order errors are logged and make {@code flattened} false; they never stop the kill. A failure to set the
     * halt flag or read orders/positions (database down) is thrown, so the caller sees the kill did not happen.
     */
    public KillResult kill(String reason) {
        lock.lock();
        try {
            tradingState.setHalted(true, reason, clock.instant());
            log.warn("Kill switch: trading halted ({})", reason);
            Outcome cancels = cancelOpenOrders();
            Outcome flattens = flattenPositions();
            boolean flattened = cancels.clean() && flattens.clean() && everythingFlat();
            KillResult result = new KillResult(cancels.count(), flattens.count(), flattened);
            log.warn("Kill switch done: {}", result);
            return result;
        } finally {
            lock.unlock();
        }
    }

    public void resume() {
        lock.lock();
        try {
            tradingState.setHalted(false, RESUMED, clock.instant());
            log.warn("Kill switch: trading resumed");
        } finally {
            lock.unlock();
        }
    }

    /** {@code count} orders handled; {@code clean} false when any of them failed. */
    private record Outcome(int count, boolean clean) {}

    private Outcome cancelOpenOrders() {
        int cancelled = 0;
        boolean clean = true;
        for (Venue venue : Venue.values()) {
            for (Order order : orders.findCheckable(venue)) {
                try {
                    Order result = orderService.cancel(order.id());
                    if (result.state() == OrderState.CANCELLED) {
                        cancelled++;
                    } else {
                        log.error("Kill switch: order {} left {}", order.id(), result.state());
                        clean = false;
                    }
                } catch (RuntimeException e) {
                    // e.g. 409: the order filled or changed meanwhile; the final open-order check decides.
                    log.warn("Kill switch: cancel of order {} failed: {}", order.id(), e.toString());
                }
            }
        }
        return new Outcome(cancelled, clean);
    }

    private Outcome flattenPositions() {
        int placed = 0;
        boolean clean = true;
        for (Position position : positions.findAll()) {
            if (position.size() == 0) {
                continue;
            }
            if (venues.stream().noneMatch(v -> v.venue() == position.venue())) {
                log.error("Kill switch: position {} {} is on {}, which has no trading venue; not flattened",
                        position.symbol(), position.size(), position.venue());
                clean = false;
                continue;
            }
            try {
                Order order = orderService.place(flattenIntent(position)).order();
                placed++;
                if (order.state() != OrderState.FILLED) {
                    log.error("Kill switch: flatten order {} for {} ended {} ({})", order.id(), position.symbol(),
                            order.state(), order.reason());
                    clean = false;
                }
            } catch (RuntimeException e) {
                log.error("Kill switch: flatten of {} {} failed", position.symbol(), position.size(), e);
                clean = false;
            }
        }
        return new Outcome(placed, clean);
    }

    private static OrderIntent flattenIntent(Position position) {
        Side side = position.size() > 0 ? Side.SELL : Side.BUY;
        return new OrderIntent("kill-" + UUID.randomUUID(), position.symbol(), side, OrderType.MARKET,
                Math.abs(position.size()), null, null, true, OrderSource.KILL_SWITCH, null, position.venue());
    }

    /** No order is in any non-terminal state (it could still reach the venue or fill) and every position is zero. */
    private boolean everythingFlat() {
        boolean noOpenOrders = orders.findNotTerminal().isEmpty();
        boolean flat = positions.findAll().stream().allMatch(p -> p.size() == 0);
        return noOpenOrders && flat;
    }
}
