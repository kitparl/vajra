package com.vajra.maryada.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RiskRulesTest {

    private static final RiskLimits LIMITS = new RiskLimits(1, 5, 10);

    private static ApprovalRequest req(String venue, String side, long size, String source, boolean reduceOnly, long position) {
        return new ApprovalRequest("c1", venue, "BTCUSD", side, size, "MARKET", BigDecimal.ONE, reduceOnly, source, position);
    }

    private static Verdict eval(ApprovalRequest r, boolean halted, long approved) {
        return RiskRules.evaluate(r, LIMITS, halted, approved);
    }

    private static ApprovalRequest buy(long position) {
        return req("PAPER", "BUY", 1, "MANUAL", false, position);
    }

    @Test
    void approvesWithinLimits() {
        Verdict v = eval(buy(0), false, 0);
        assertThat(v.approved()).isTrue();
        assertThat(v.reason()).isNull();
    }

    @Test
    void haltedRejectsNormalOrders() {
        assertThat(eval(buy(0), true, 0)).isEqualTo(new Verdict(false, "HALTED"));
    }

    @Test
    void flattenIsApprovedWhileHaltedAndIgnoresSizeAndRate() {
        Verdict v = eval(req("PAPER", "SELL", 5, "KILL_SWITCH", true, 5), true, 10);
        assertThat(v.approved()).isTrue();
    }

    @Test
    void killSwitchSourceWithoutReduceOnlyIsNotExempt() {
        assertThat(eval(req("PAPER", "BUY", 1, "KILL_SWITCH", false, 0), true, 0).reason()).isEqualTo("HALTED");
    }

    @Test
    void unknownVenueRejected() {
        assertThat(eval(req("BINANCE", "BUY", 1, "MANUAL", false, 0), false, 0))
                .isEqualTo(new Verdict(false, "VENUE_NOT_ALLOWED"));
    }

    @Test
    void deltaTestnetAllowed() {
        assertThat(eval(req("DELTA_TESTNET", "BUY", 1, "MANUAL", false, 0), false, 0).approved()).isTrue();
    }

    @Test
    void orderSizeLimit() {
        assertThat(eval(req("PAPER", "BUY", 2, "MANUAL", false, 0), false, 0).reason()).isEqualTo("ORDER_SIZE_LIMIT");
    }

    @Test
    void positionLimit() {
        assertThat(eval(buy(5), false, 0).reason()).isEqualTo("POSITION_LIMIT");
        assertThat(eval(req("PAPER", "SELL", 1, "MANUAL", false, -5), false, 0).reason()).isEqualTo("POSITION_LIMIT");
        assertThat(eval(req("PAPER", "SELL", 1, "MANUAL", false, 5), false, 0).approved()).isTrue();
        assertThat(eval(buy(0), false, 0).approved()).isTrue();
    }

    @Test
    void orderRateLimit() {
        assertThat(eval(buy(0), false, 10).reason()).isEqualTo("ORDER_RATE_LIMIT");
        assertThat(eval(buy(0), false, 9).approved()).isTrue();
    }

    @Test
    void invalidSideRejected() {
        assertThat(eval(req("PAPER", "HOLD", 1, "MANUAL", false, 0), false, 0).reason()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void invalidSourceAndNullSideRejected() {
        assertThat(eval(req("PAPER", "BUY", 1, "BOT", false, 0), false, 0).reason()).isEqualTo("INVALID_REQUEST");
        assertThat(eval(req("PAPER", null, 1, "MANUAL", false, 0), false, 0).reason()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void positionOverflowIsRejected() {
        assertThat(eval(buy(Long.MAX_VALUE), false, 0)).isEqualTo(new Verdict(false, "INVALID_REQUEST"));
        assertThat(eval(req("PAPER", "SELL", 1, "MANUAL", false, Long.MIN_VALUE), false, 0))
                .isEqualTo(new Verdict(false, "INVALID_REQUEST"));
    }

    @Test
    void nonPositiveSizeIsRejected() {
        assertThat(eval(req("PAPER", "BUY", 0, "MANUAL", false, 0), false, 0).reason()).isEqualTo("INVALID_REQUEST");
        assertThat(eval(req("PAPER", "BUY", -5, "MANUAL", false, 5), false, 0).reason()).isEqualTo("INVALID_REQUEST");
    }
}
