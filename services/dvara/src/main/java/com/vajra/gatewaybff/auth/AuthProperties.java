package com.vajra.gatewaybff.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code vajra.auth.*}: the single terminal user. {@code passwordHash} is a bcrypt hash made with
 * {@code scripts/gateway-password-hash.sh}. {@code cookieSecure} must stay true except on plain-http local dev.
 */
@ConfigurationProperties("vajra.auth")
record AuthProperties(String username, String passwordHash, @DefaultValue("true") boolean cookieSecure) {

    AuthProperties {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("vajra.auth.username (GATEWAY_ADMIN_USERNAME) must be set");
        }
        if (passwordHash == null || !passwordHash.startsWith("$2")) {
            throw new IllegalArgumentException(
                    "vajra.auth.password-hash (GATEWAY_ADMIN_PASSWORD_HASH) must be set to a bcrypt hash; "
                            + "create one with scripts/gateway-password-hash.sh");
        }
    }
}
