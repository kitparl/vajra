package com.vajra.coreservice.connector.delta;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Delta request signature: lowercase hex HMAC-SHA256(secret, method + timestamp + path + query + body). */
final class DeltaSigner {

    private DeltaSigner() {}

    /** @param query the query string with its leading {@code ?}, or empty */
    static String sign(String secret, String method, long timestampSeconds, String path, String query, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String message = method + timestampSeconds + path + query + body;
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // Never wrap e: the key spec could carry the secret in its message.
            throw new IllegalStateException("HMAC-SHA256 is not available");
        }
    }
}
