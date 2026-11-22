package com.vajra.coreservice.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.connector.VenueResult;
import com.vajra.coreservice.connector.delta.DeltaFill;
import com.vajra.coreservice.connector.delta.DeltaPosition;
import com.vajra.coreservice.connector.delta.DeltaPrivateClient;
import com.vajra.coreservice.connector.delta.DeltaTradingVenue;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import com.vajra.coreservice.position.Position;
import com.vajra.coreservice.position.PositionRepository;
import com.vajra.coreservice.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Decision rules of the reconciler with every collaborator mocked and a fixed clock. */
@ExtendWith(OutputCaptureExtension.class)
class ReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private final OrderRepository orders = mock(OrderRepository.class);
    private final FillRepository fills = mock(FillRepository.class);
    private final PositionRepository positions = mock(PositionRepository.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final OrderService orderService = mock(OrderService.class);
    private final DeltaTradingVenue venue = mock(DeltaTradingVenue.class);
    private final DeltaPrivateClient client = mock(DeltaPrivateClient.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final Instrument btc = new Instrument(Venue.DELTA_TESTNET, "BTCUSD", 27, "perpetual_futures",
            new BigDecimal("0.001"), new BigDecimal("0.5"), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, true, NOW);

    private Reconciler reconciler() {
        return new Reconciler(orders, fills, positions, instruments, orderService, Optional.of(venue), client, clock);
    }

    @Test
    void unknownOrderNotFoundIsRejectedOnlyOnceOlderThan60Seconds() {
        Order old = order(OrderState.UNKNOWN, Venue.DELTA_TESTNET, NOW.minusSeconds(61));
        Order young = order(OrderState.UNKNOWN, Venue.DELTA_TESTNET, NOW.minusSeconds(59));
        when(orders.findAllByState(OrderState.UNKNOWN)).thenReturn(List.of(old, young));
        when(instruments.find(any(), anyString())).thenReturn(Optional.of(btc));
        when(venue.resolve(any(), any())).thenReturn(Optional.empty());

        reconciler().run();

        verify(orderService).rejectUnknown(old.id(), "NOT_FOUND_AT_VENUE");
        verify(orderService, never()).rejectUnknown(young.id(), "NOT_FOUND_AT_VENUE");
    }

    @Test
    void foundUnknownOrderIsResolvedThroughOrderService() {
        Order unknown = order(OrderState.UNKNOWN, Venue.DELTA_TESTNET, NOW.minusSeconds(5));
        VenueResult resting = new VenueResult.Resting("555");
        when(orders.findAllByState(OrderState.UNKNOWN)).thenReturn(List.of(unknown));
        when(instruments.find(any(), anyString())).thenReturn(Optional.of(btc));
        when(venue.resolve(unknown, btc)).thenReturn(Optional.of(resting));

        Reconciler.ReconcileSummary summary = reconciler().run();

        verify(orderService).resolveUnknown(unknown.id(), resting, btc);
        assertThat(summary.resolved()).isEqualTo(1);
    }

    @Test
    void fillIdAlreadyInsideAMergedIdIsSkipped() {
        when(client.fills(any())).thenReturn(List.of(deltaFill("f-2", 555)));
        when(fills.containsVenueFillId(Venue.DELTA_TESTNET, "f-2")).thenReturn(true);

        reconciler().run();

        verify(orders, never()).findByVenueOrderId(any(), anyString());
        verify(orderService, never()).applyResolved(any(), any(), any());
    }

    @Test
    void missingFillOfOpenOrderIsResolvedPerOrderNotPerFill() {
        Order open = order(OrderState.ACKED, Venue.DELTA_TESTNET, NOW);
        VenueResult filled = new VenueResult.Filled("555", "f-1,f-2", BigDecimal.TEN, BigDecimal.ZERO, "TAKER");
        when(client.fills(any())).thenReturn(List.of(deltaFill("f-1", 555), deltaFill("f-2", 555)));
        when(orders.findByVenueOrderId(Venue.DELTA_TESTNET, "555")).thenReturn(Optional.of(open));
        when(instruments.find(any(), anyString())).thenReturn(Optional.of(btc));
        when(venue.resolve(open, btc)).thenReturn(Optional.of(filled));

        reconciler().run();

        verify(orderService).applyResolved(open, filled, btc);
    }

    @Test
    void fillOfAlreadyFilledOrderIsAnErrorNeverRecorded(CapturedOutput output) {
        Order filledOrder = order(OrderState.FILLED, Venue.DELTA_TESTNET, NOW);
        when(client.fills(any())).thenReturn(List.of(deltaFill("f-9", 555)));
        when(orders.findByVenueOrderId(Venue.DELTA_TESTNET, "555")).thenReturn(Optional.of(filledOrder));
        when(fills.findByOrder(filledOrder.id())).thenReturn(List.of());

        Reconciler.ReconcileSummary summary = reconciler().run();

        assertThat(output).contains("ERROR").contains("f-9");
        assertThat(summary.mismatches()).isEqualTo(1);
        verify(orderService, never()).applyResolved(any(), any(), any());
        verify(venue, never()).resolve(any(), any());
    }

    @Test
    void positionRules(CapturedOutput output) {
        when(instruments.findTradingEnabled()).thenReturn(List.of(btc));
        when(instruments.idOf(Venue.DELTA_TESTNET, "BTCUSD")).thenReturn(Optional.of(1L));
        when(positions.find(Venue.DELTA_TESTNET, 1L)).thenReturn(Optional.of(position(2, "50000")));

        when(client.position(27)).thenReturn(new DeltaPosition(2, new BigDecimal("50000.5")));
        assertThat(reconciler().run().mismatches()).as("within one tick").isZero();

        when(client.position(27)).thenReturn(new DeltaPosition(2, new BigDecimal("50000.6")));
        assertThat(reconciler().run().mismatches()).as("beyond one tick").isEqualTo(1);
        assertThat(output).contains("ours=50000").contains("Delta=50000.6");

        when(client.position(27)).thenReturn(new DeltaPosition(-2, new BigDecimal("50000")));
        assertThat(reconciler().run().mismatches()).as("size differs").isEqualTo(1);

        when(positions.find(Venue.DELTA_TESTNET, 1L)).thenReturn(Optional.empty());
        when(client.position(27)).thenReturn(new DeltaPosition(0, BigDecimal.ZERO));
        assertThat(reconciler().run().mismatches()).as("both flat").isZero();
        verify(positions, never()).save(any());
    }

    @Test
    void failingStepDoesNotStopLaterSteps() {
        when(orders.findByStateBefore(any(), any())).thenThrow(new IllegalStateException("db down"));
        when(orders.findAllByState(any())).thenThrow(new IllegalStateException("db down"));
        when(client.fills(any())).thenThrow(new IllegalStateException("delta down"));
        when(instruments.findTradingEnabled()).thenReturn(List.of(btc));
        when(client.position(27)).thenReturn(new DeltaPosition(0, BigDecimal.ZERO));

        reconciler().run();

        verify(client).position(27);
    }

    @Test
    void withoutDeltaVenueOnlyTheVenueFreeStepsRun() {
        Order paperUnknown = order(OrderState.UNKNOWN, Venue.PAPER, NOW.minusSeconds(100));
        when(orders.findAllByState(OrderState.UNKNOWN)).thenReturn(List.of(paperUnknown));

        new Reconciler(orders, fills, positions, instruments, orderService, Optional.empty(), client, clock).run();

        verifyNoInteractions(client);
        verify(orderService, never()).rejectUnknown(any(), anyString());
    }

    private static Position position(long size, String entry) {
        return new Position(Venue.DELTA_TESTNET, 1, "BTCUSD", size, new BigDecimal(entry), BigDecimal.ZERO, BigDecimal.ZERO, NOW, 1);
    }

    private static DeltaFill deltaFill(String id, long orderId) {
        return new DeltaFill(id, orderId, 1, BigDecimal.TEN, BigDecimal.ZERO, "taker", NOW);
    }

    private static Order order(OrderState state, Venue venue, Instant updatedAt) {
        return new Order(UUID.randomUUID(), "c-" + UUID.randomUUID(), "k", venue, 1, "BTCUSD", Side.BUY, OrderType.LIMIT, 1,
                BigDecimal.ONE, TimeInForce.GTC, false, state, 0, null, BigDecimal.ZERO, "555", OrderSource.MANUAL, null,
                updatedAt, updatedAt, 0);
    }
}
