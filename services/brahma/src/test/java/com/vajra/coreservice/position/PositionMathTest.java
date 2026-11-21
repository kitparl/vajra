package com.vajra.coreservice.position;

import static org.assertj.core.api.Assertions.assertThat;

import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.oms.Side;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PositionMathTest {

    private static final BigDecimal CV = new BigDecimal("0.001");
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static Position flat() {
        return Position.flat(Venue.PAPER, 1L, "BTCUSD");
    }

    private static Position fill(Position p, Side side, long size, String price) {
        return PositionMath.applyFill(p, side, size, bd(price), CV, BigDecimal.ZERO, T);
    }

    @Test
    void openLong() {
        Position p = fill(flat(), Side.BUY, 2, "50000");
        assertThat(p.size()).isEqualTo(2);
        assertThat(p.avgEntryPrice()).isEqualByComparingTo("50000");
        assertThat(p.realizedPnl()).isEqualByComparingTo("0");
        assertThat(p.updatedAt()).isEqualTo(T);
        assertThat(p.symbol()).isEqualTo("BTCUSD");
    }

    @Test
    void addAveragesEntry() {
        Position p = fill(fill(flat(), Side.BUY, 2, "50000"), Side.BUY, 2, "52000");
        assertThat(p.size()).isEqualTo(4);
        assertThat(p.avgEntryPrice()).isEqualByComparingTo("51000");
    }

    @Test
    void reduceRealizesPnl() {
        Position p = fill(fill(flat(), Side.BUY, 4, "51000"), Side.SELL, 1, "53000");
        assertThat(p.size()).isEqualTo(3);
        assertThat(p.avgEntryPrice()).isEqualByComparingTo("51000");
        assertThat(p.realizedPnl()).isEqualByComparingTo("2.000");
    }

    @Test
    void closeToZeroResetsEntry() {
        Position p = fill(fill(flat(), Side.BUY, 3, "51000"), Side.SELL, 3, "50000");
        assertThat(p.size()).isZero();
        assertThat(p.avgEntryPrice()).isEqualByComparingTo("0");
        assertThat(p.realizedPnl()).isEqualByComparingTo("-3");
    }

    @Test
    void flipThroughZeroClosesThenOpens() {
        Position p = fill(fill(flat(), Side.BUY, 2, "50000"), Side.SELL, 5, "51000");
        assertThat(p.realizedPnl()).isEqualByComparingTo("2");
        assertThat(p.size()).isEqualTo(-3);
        assertThat(p.avgEntryPrice()).isEqualByComparingTo("51000");
    }

    @Test
    void shortProfitWhenPriceFalls() {
        Position p = fill(fill(flat(), Side.SELL, 2, "50000"), Side.BUY, 2, "49000");
        assertThat(p.realizedPnl()).isEqualByComparingTo("2");
        assertThat(p.size()).isZero();
    }

    @Test
    void feesAccumulate() {
        Position p = PositionMath.applyFill(flat(), Side.BUY, 2, bd("50000"), CV, bd("0.05"), T);
        p = PositionMath.applyFill(p, Side.BUY, 1, bd("50000"), CV, bd("0.025"), T);
        assertThat(p.feesPaid()).isEqualByComparingTo("0.075");
        assertThat(PositionMath.fee(2, bd("50000"), CV, bd("0.0005"))).isEqualByComparingTo("0.05");
    }

    @Test
    void unrealizedPnl() {
        Position l = fill(flat(), Side.BUY, 2, "50000");
        Position s = fill(flat(), Side.SELL, 2, "50000");
        assertThat(PositionMath.unrealizedPnl(l, bd("50500"), CV)).isEqualByComparingTo("1.000");
        assertThat(PositionMath.unrealizedPnl(s, bd("50500"), CV)).isEqualByComparingTo("-1");
        assertThat(PositionMath.unrealizedPnl(flat(), bd("50500"), CV)).isEqualByComparingTo("0");
    }

    @Test
    void increasesOrFlips() {
        assertThat(PositionMath.increasesOrFlips(0, Side.BUY, 1)).isTrue();
        assertThat(PositionMath.increasesOrFlips(2, Side.BUY, 1)).isTrue();
        assertThat(PositionMath.increasesOrFlips(2, Side.SELL, 3)).isTrue();
        assertThat(PositionMath.increasesOrFlips(-2, Side.BUY, 3)).isTrue();
        assertThat(PositionMath.increasesOrFlips(2, Side.SELL, 2)).isFalse();
        assertThat(PositionMath.increasesOrFlips(2, Side.SELL, 1)).isFalse();
        assertThat(PositionMath.increasesOrFlips(-2, Side.BUY, 2)).isFalse();
    }
}
