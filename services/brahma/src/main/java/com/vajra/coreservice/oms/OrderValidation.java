package com.vajra.coreservice.oms;

import com.vajra.coreservice.api.ApiException;
import com.vajra.coreservice.instrument.Instrument;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Input checks for a new order; every failure is a 400, raised before anything is stored or sent. */
final class OrderValidation {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final int MAX_DIGITS = 12;

    private OrderValidation() {}

    /** Checks the fields that need no lookup and resolves the time in force (LIMIT defaults to GTC, MARKET is IOC). */
    static OrderIntent normalize(OrderIntent i) {
        if (i.idempotencyKey() == null || !IDEMPOTENCY_KEY.matcher(i.idempotencyKey()).matches()) {
            throw ApiException.badRequest("Idempotency-Key must be 1-64 characters of [A-Za-z0-9_-]");
        }
        if (i.symbol() == null || i.symbol().isBlank() || i.side() == null || i.type() == null || i.source() == null) {
            throw ApiException.badRequest("symbol, side, type and source are required");
        }
        if (i.size() <= 0) {
            throw ApiException.badRequest("size must be positive");
        }
        return new OrderIntent(i.idempotencyKey(), i.symbol(), i.side(), i.type(), i.size(), i.limitPrice(),
                timeInForce(i), i.reduceOnly(), i.source());
    }

    /** Checks the intent against the instrument it trades. */
    static void checkInstrument(OrderIntent i, Instrument instrument) {
        if (!instrument.tradingEnabled()) {
            throw ApiException.badRequest("trading is disabled for " + i.symbol());
        }
        if (BigDecimal.valueOf(i.size()).compareTo(instrument.minSize()) < 0) {
            throw ApiException.badRequest("size is below the minimum of " + instrument.minSize().toPlainString());
        }
        if (i.type() == OrderType.LIMIT && i.limitPrice().remainder(instrument.tickSize()).signum() != 0) {
            throw ApiException.badRequest("limitPrice must be a multiple of " + instrument.tickSize().toPlainString());
        }
    }

    /** Rejects absurd exponents before any arithmetic or hashing, which would otherwise burn CPU and memory. */
    private static void requireBoundedDigits(BigDecimal price) {
        if (price.scale() > MAX_DIGITS || (long) price.precision() - price.scale() > MAX_DIGITS) {
            throw ApiException.badRequest(
                    "limitPrice may have at most " + MAX_DIGITS + " integer and " + MAX_DIGITS + " fractional digits");
        }
    }

    private static TimeInForce timeInForce(OrderIntent i) {
        if (i.type() == OrderType.MARKET) {
            if (i.limitPrice() != null) {
                throw ApiException.badRequest("MARKET orders take no limitPrice");
            }
            if (i.timeInForce() == TimeInForce.GTC) {
                throw ApiException.badRequest("MARKET orders are always IOC");
            }
            return TimeInForce.IOC;
        }
        if (i.limitPrice() == null || i.limitPrice().signum() <= 0) {
            throw ApiException.badRequest("LIMIT orders need a positive limitPrice");
        }
        requireBoundedDigits(i.limitPrice());
        return i.timeInForce() == null ? TimeInForce.GTC : i.timeInForce();
    }
}
