package com.vajra.brahma.oms;

import static com.vajra.brahma.oms.OrderState.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderStateTest {

    private static final List<OrderState[]> ALLOWED = List.of(
            pair(NEW, RISK_REJECTED), pair(NEW, APPROVED),
            pair(APPROVED, SENT),
            pair(SENT, ACKED), pair(SENT, REJECTED), pair(SENT, UNKNOWN),
            pair(ACKED, PARTIALLY_FILLED), pair(ACKED, FILLED), pair(ACKED, CANCEL_REQUESTED), pair(ACKED, EXPIRED),
            pair(PARTIALLY_FILLED, PARTIALLY_FILLED), pair(PARTIALLY_FILLED, FILLED),
            pair(PARTIALLY_FILLED, CANCEL_REQUESTED), pair(PARTIALLY_FILLED, EXPIRED),
            pair(CANCEL_REQUESTED, CANCELLED), pair(CANCEL_REQUESTED, FILLED),
            pair(UNKNOWN, ACKED), pair(UNKNOWN, PARTIALLY_FILLED), pair(UNKNOWN, FILLED),
            pair(UNKNOWN, REJECTED), pair(UNKNOWN, CANCELLED));

    private static OrderState[] pair(OrderState from, OrderState to) {
        return new OrderState[] {from, to};
    }

    static Stream<Arguments> allowed() {
        return ALLOWED.stream().map(p -> Arguments.of(p[0], p[1]));
    }

    @ParameterizedTest
    @MethodSource("allowed")
    void allowedTransitions(OrderState from, OrderState to) {
        assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isTrue();
    }

    @Test
    void everyOtherTransitionIsForbidden() {
        Set<String> allowed = new HashSet<>();
        ALLOWED.forEach(p -> allowed.add(p[0] + ">" + p[1]));
        int checked = 0;
        for (OrderState from : values()) {
            for (OrderState to : values()) {
                if (!allowed.contains(from + ">" + to)) {
                    assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isFalse();
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(144 - ALLOWED.size());
    }

    @Test
    void terminalStatesHaveNoExit() {
        for (OrderState t : List.of(RISK_REJECTED, FILLED, REJECTED, CANCELLED, EXPIRED)) {
            assertThat(t.isTerminal()).isTrue();
            for (OrderState x : values()) {
                assertThat(t.canTransitionTo(x)).as("%s -> %s", t, x).isFalse();
            }
        }
        assertThat(NEW.isTerminal()).isFalse();
        assertThat(UNKNOWN.isTerminal()).isFalse();
    }

    @Test
    void openStates() {
        for (OrderState s : values()) {
            assertThat(s.isOpen()).isEqualTo(s == ACKED || s == PARTIALLY_FILLED);
        }
    }
}
