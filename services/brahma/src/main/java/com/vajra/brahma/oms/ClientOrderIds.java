package com.vajra.brahma.oms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Derives the venue-facing client order id from the caller's idempotency key. */
public final class ClientOrderIds {

    private static final int HEX_CHARS = 31;

    private ClientOrderIds() {}

    /** {@code "v"} + first 31 hex chars of SHA-256(UTF-8 key): deterministic, 32 chars. */
    public static String fromKey(String idempotencyKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            return "v" + HexFormat.of().formatHex(digest).substring(0, HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
