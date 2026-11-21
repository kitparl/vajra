package com.vajra.brahma.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vajra.brahma.audit.AuditWriter;
import com.vajra.brahma.connector.Venue;
import com.vajra.brahma.connector.VenueResult;
import com.vajra.brahma.instrument.Instrument;
import com.vajra.brahma.position.PositionRepository;
import com.vajra.brahma.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The fill step's retry on optimistic-lock conflicts; the happy path is covered end to end in OrderServiceIT. */
class FillRecorderTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final Instrument BTC = new Instrument(Venue.DELTA_TESTNET, "BTCUSD", 84, "perpetual_futures",
            new BigDecimal("0.001"), new BigDecimal("0.1"), BigDecimal.ONE, new BigDecimal("0.0002"),
            new BigDecimal("0.0005"), true, NOW);
    private static final VenueResult.Filled FILLED =
            new VenueResult.Filled("paper-1", "paper-fill-1", new BigDecimal("50010"), new BigDecimal("0.025005"), "TAKER");

    private final OrderRepository orders = mock(OrderRepository.class);
    private final FillRepository fills = mock(FillRepository.class);
    private final PositionRepository positions = mock(PositionRepository.class);
    private final AuditWriter audit = mock(AuditWriter.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final FillRecorder recorder = new FillRecorder(new TransactionTemplate(mock(PlatformTransactionManager.class)),
            orders, fills, positions, new OrderLifecycle(orders, audit, clock), audit, clock);
    private final Order acked = order(OrderState.ACKED);

    @BeforeEach
    void stubs() {
        when(orders.find(acked.id())).thenReturn(Optional.of(acked));
        when(orders.update(any())).thenAnswer(inv -> bumped(inv.getArgument(0)));
        when(fills.insertIfNew(any())).thenReturn(true);
        when(positions.find(Venue.PAPER, 1L)).thenReturn(Optional.empty());
    }

    @Test
    void fillRetriesOnOptimisticLockWithFreshState() {
        when(positions.save(any()))
                .thenThrow(new OptimisticLockingFailureException("raced"))
                .thenAnswer(inv -> inv.getArgument(0));

        Order result = recorder.record(acked.id(), FILLED, BTC);

        assertThat(result.state()).isEqualTo(OrderState.FILLED);
        assertThat(result.filledSize()).isEqualTo(1);
        verify(orders, times(2)).find(acked.id());
        verify(positions, times(2)).find(Venue.PAPER, 1L);
    }

    @Test
    void fillGivesUpAfterThreeAttempts() {
        when(positions.save(any())).thenThrow(new OptimisticLockingFailureException("raced"));

        assertThatThrownBy(() -> recorder.record(acked.id(), FILLED, BTC))
                .isInstanceOf(OptimisticLockingFailureException.class);
        verify(positions, times(3)).save(any());
    }

    @Test
    void orderNoLongerFillableIsNotFilled() {
        Order cancelled = order(OrderState.CANCELLED);
        when(orders.find(acked.id())).thenReturn(Optional.of(cancelled));

        Order result = recorder.record(acked.id(), FILLED, BTC);

        assertThat(result.state()).isEqualTo(OrderState.CANCELLED);
        verify(fills, never()).insertIfNew(any());
        verify(positions, never()).save(any());
    }

    @Test
    void replayedFillIsIgnored() {
        when(fills.insertIfNew(any())).thenReturn(false);

        Order result = recorder.record(acked.id(), FILLED, BTC);

        assertThat(result.state()).isEqualTo(OrderState.ACKED);
        verify(positions, never()).save(any());
    }

    private static Order order(OrderState state) {
        return new Order(UUID.fromString("00000000-0000-0000-0000-000000000001"), "cid", "key", Venue.PAPER, 1L,
                "BTCUSD", Side.BUY, OrderType.MARKET, 1L, null, TimeInForce.IOC, false, state, 0L, null,
                BigDecimal.ZERO, null, OrderSource.MANUAL, null, NOW, NOW, 3L);
    }

    private static Order bumped(Order o) {
        return new Order(o.id(), o.clientOrderId(), o.idempotencyKey(), o.venue(), o.instrumentId(), o.symbol(),
                o.side(), o.type(), o.size(), o.limitPrice(), o.timeInForce(), o.reduceOnly(), o.state(),
                o.filledSize(), o.avgFillPrice(), o.fees(), o.venueOrderId(), o.source(), o.reason(), o.createdAt(),
                o.updatedAt(), o.version() + 1);
    }
}
