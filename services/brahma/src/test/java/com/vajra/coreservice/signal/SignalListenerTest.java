package com.vajra.coreservice.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vajra.coreservice.api.ApiException;
import com.vajra.coreservice.oms.OrderIntent;
import com.vajra.coreservice.oms.OrderService;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.oms.Order;
import com.vajra.coreservice.oms.OrderSource;
import com.vajra.coreservice.oms.OrderState;
import com.vajra.coreservice.oms.OrderType;
import com.vajra.coreservice.oms.PlaceOrderResult;
import com.vajra.coreservice.oms.Side;
import com.vajra.coreservice.oms.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(OutputCaptureExtension.class)
class SignalListenerTest {

    private final OrderService orders = mock(OrderService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final SignalListener listener = new SignalListener(new SignalMapper(), orders);

    @Test
    void retryableConflictIsNotAcknowledged() {
        ApiException transientConflict = ApiException.retryableConflict("order changed, retry");
        when(orders.place(any(OrderIntent.class))).thenThrow(transientConflict);

        assertThatThrownBy(() -> listener.onMessage(record(), ack)).isSameAs(transientConflict);
        verify(ack, never()).acknowledge();
    }

    @Test
    void duplicateKeyWithDifferentBodyIsSkippedAndAcknowledged() {
        when(orders.place(any(OrderIntent.class)))
                .thenThrow(ApiException.conflict("Idempotency-Key already used for a different order"));

        listener.onMessage(record(), ack);

        verify(ack).acknowledge();
    }

    @Test
    void unexpectedExceptionIsNotAcknowledged() {
        when(orders.place(any(OrderIntent.class))).thenThrow(new IllegalStateException("db down"));

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> listener.onMessage(record(), ack))).hasMessage("db down");
        verify(ack, never()).acknowledge();
    }

    @Test
    void duplicateWithStuckOrderIsLoggedAtErrorAndAcknowledged(CapturedOutput output) {
        Order stuck = order(OrderState.APPROVED);
        when(orders.place(any(OrderIntent.class))).thenReturn(new PlaceOrderResult(stuck, false));

        listener.onMessage(record(), ack);

        verify(ack).acknowledge();
        assertThat(output.getAll()).contains("ERROR")
                .contains("signal " + SIGNAL_ID + ": order " + stuck.id() + " stuck in APPROVED, not re-processed");
    }

    @Test
    void duplicateWithTerminalOrderIsAcknowledgedWithoutError(CapturedOutput output) {
        when(orders.place(any(OrderIntent.class))).thenReturn(new PlaceOrderResult(order(OrderState.FILLED), false));

        listener.onMessage(record(), ack);

        verify(ack).acknowledge();
        assertThat(output.getAll()).doesNotContain("ERROR").doesNotContain("stuck");
    }

    private static final String SIGNAL_ID = UUID.randomUUID().toString();

    private static Order order(OrderState state) {
        Instant now = Instant.parse("2026-11-22T10:00:00Z");
        return new Order(UUID.randomUUID(), "cid", "signal-" + SIGNAL_ID, Venue.PAPER, 1, "BTCUSD", Side.BUY,
                OrderType.MARKET, 1, null, TimeInForce.IOC, false, state, 0, null, BigDecimal.ZERO, null,
                OrderSource.SIGNAL, null, now, now, 0);
    }

    private static ConsumerRecord<String, byte[]> record() {
        byte[] value = SignalMapperTest.envelope(
                SignalMapperTest.signal(SIGNAL_ID, "BTCUSD", "BUY", "MARKET", 1));
        return new ConsumerRecord<>("signal.generated", 0, 7L, "BTCUSD", value);
    }
}
