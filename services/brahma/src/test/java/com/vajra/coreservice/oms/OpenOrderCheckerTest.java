package com.vajra.coreservice.oms;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vajra.coreservice.connector.TradingVenue;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpenOrderCheckerTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderService orderService = mock(OrderService.class);
    private final MutableClock clock = new MutableClock(NOW);

    @Test
    void deltaOrderIsCheckedOnlyEveryTwoSecondsPaperEverySecond() {
        TradingVenue delta = venue(Venue.DELTA_TESTNET);
        TradingVenue paper = venue(Venue.PAPER);
        Order deltaOrder = order(Venue.DELTA_TESTNET);
        Order paperOrder = order(Venue.PAPER);
        when(orders.findCheckable(Venue.DELTA_TESTNET)).thenReturn(List.of(deltaOrder));
        when(orders.findCheckable(Venue.PAPER)).thenReturn(List.of(paperOrder));
        OpenOrderChecker checker = new OpenOrderChecker(List.of(delta, paper), orders, orderService, clock);

        checker.run();
        clock.advance(Duration.ofSeconds(1));
        checker.run();
        clock.advance(Duration.ofSeconds(1));
        checker.run();

        verify(orderService, times(2)).onRestingCheck(deltaOrder);
        verify(orderService, times(3)).onRestingCheck(paperOrder);
    }

    @Test
    void failingOrderDoesNotStopTheRest() {
        TradingVenue paper = venue(Venue.PAPER);
        Order first = order(Venue.PAPER);
        Order second = order(Venue.PAPER);
        when(orders.findCheckable(Venue.PAPER)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("boom")).when(orderService).onRestingCheck(first);

        new OpenOrderChecker(List.of(paper), orders, orderService, clock).run();

        verify(orderService).onRestingCheck(second);
    }

    private static TradingVenue venue(Venue venue) {
        TradingVenue v = mock(TradingVenue.class);
        when(v.venue()).thenReturn(venue);
        return v;
    }

    private static Order order(Venue venue) {
        return new Order(UUID.randomUUID(), "c", "k", venue, 1, "BTCUSD", Side.BUY, OrderType.LIMIT, 1, BigDecimal.ONE,
                TimeInForce.GTC, false, OrderState.ACKED, 0, null, BigDecimal.ZERO, "v", OrderSource.MANUAL, null, NOW,
                NOW, 0);
    }
}
