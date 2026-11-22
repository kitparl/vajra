package com.vajra.coreservice.oms;

import com.vajra.coreservice.connector.Venue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * What the caller asks for. The idempotency key identifies the request; every other field is its body. {@code signalId}
 * is null for manual and kill-switch orders. {@code venue} is set only by kill-switch flattens, which must trade on
 * the venue where the position sits; null means the venue of the trading mode. It is not part of {@link #requestHash}.
 */
public record OrderIntent(
        String idempotencyKey,
        String symbol,
        Side side,
        OrderType type,
        long size,
        BigDecimal limitPrice,
        TimeInForce timeInForce,
        boolean reduceOnly,
        OrderSource source,
        UUID signalId,
        Venue venue) {

    /** An order on the venue of the trading mode (manual and signal orders). */
    public OrderIntent(String idempotencyKey, String symbol, Side side, OrderType type, long size,
            BigDecimal limitPrice, TimeInForce timeInForce, boolean reduceOnly, OrderSource source, UUID signalId) {
        this(idempotencyKey, symbol, side, type, size, limitPrice, timeInForce, reduceOnly, source, signalId, null);
    }

    /**
     * SHA-256 hex of every field except the key. A replay with the same key must have the same hash. Prices are
     * compared by value ({@code 50000} and {@code 50000.0} hash the same).
     */
    public String requestHash() {
        String body = String.join("|",
                Objects.toString(symbol, ""),
                Objects.toString(side, ""),
                Objects.toString(type, ""),
                Long.toString(size),
                limitPrice == null ? "" : limitPrice.stripTrailingZeros().toPlainString(),
                Objects.toString(timeInForce, ""),
                Boolean.toString(reduceOnly),
                Objects.toString(source, ""));
        // Appended only for signal orders so the stored hashes of manual and kill orders stay valid.
        if (signalId != null) {
            body += "|" + signalId;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
