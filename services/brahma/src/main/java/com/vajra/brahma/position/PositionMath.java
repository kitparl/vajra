package com.vajra.brahma.position;

import com.vajra.brahma.oms.Side;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;

/** Pure position arithmetic: average entry, realized/unrealized PnL and fees. */
public final class PositionMath {

    private PositionMath() {}

    /** Applies a fill to a position and returns the new position (the input is not modified). */
    public static Position applyFill(
            Position p, Side side, long size, BigDecimal price, BigDecimal contractValue, BigDecimal fee, Instant at) {
        long signedFill = side.sign() * size;
        long oldSize = p.size();
        long newSize = oldSize + signedFill;
        BigDecimal realized = p.realizedPnl();
        BigDecimal avg = p.avgEntryPrice();

        boolean increases = oldSize == 0 || Long.signum(oldSize) == Long.signum(signedFill);
        if (increases) {
            long oldAbs = Math.abs(oldSize);
            avg = BigDecimal.valueOf(oldAbs)
                    .multiply(avg)
                    .add(BigDecimal.valueOf(size).multiply(price))
                    .divide(BigDecimal.valueOf(oldAbs + size), MathContext.DECIMAL128);
        } else {
            long closed = Math.min(Math.abs(oldSize), size);
            realized = realized.add(BigDecimal.valueOf(closed)
                    .multiply(contractValue)
                    .multiply(price.subtract(avg))
                    .multiply(BigDecimal.valueOf(Long.signum(oldSize))));
            if (newSize == 0) {
                avg = BigDecimal.ZERO;
            } else if (Long.signum(newSize) != Long.signum(oldSize)) {
                avg = price;
            }
        }

        return new Position(
                p.venue(),
                p.instrumentId(),
                p.symbol(),
                newSize,
                avg,
                realized,
                p.feesPaid().add(fee),
                at,
                p.version());
    }

    /** True when a fill of {@code size} on {@code side} would grow the position or flip its side (reduce-only rule). */
    public static boolean increasesOrFlips(long currentSize, Side side, long size) {
        long next = currentSize + side.sign() * size;
        boolean flips = next != 0 && Long.signum(next) != Long.signum(currentSize);
        return Math.abs(next) > Math.abs(currentSize) || flips;
    }

    /** Fee = size * contractValue * price * rate. */
    public static BigDecimal fee(long size, BigDecimal price, BigDecimal contractValue, BigDecimal rate) {
        return BigDecimal.valueOf(size).multiply(contractValue).multiply(price).multiply(rate);
    }

    /** Unrealized PnL = size * contractValue * (mark - avg); zero when flat. */
    public static BigDecimal unrealizedPnl(Position p, BigDecimal markPrice, BigDecimal contractValue) {
        if (p.size() == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(p.size()).multiply(contractValue).multiply(markPrice.subtract(p.avgEntryPrice()));
    }
}
