package com.vajra.gatewaybff.live;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code vajra.live.*}: the one browser origin allowed to open {@code /ws}, and how often upstreams are polled. */
@ConfigurationProperties("vajra.live")
public record LiveProperties(
        String uiOrigin,
        @DefaultValue("PT1S") Duration quoteInterval,
        @DefaultValue("PT2S") Duration riskInterval) {

    public LiveProperties {
        if (!isPlainOrigin(uiOrigin)) {
            throw new IllegalArgumentException(
                    "vajra.live.ui-origin (GATEWAY_UI_ORIGIN) must be scheme://host[:port] with no path or trailing /");
        }
        if (quoteInterval.isZero() || quoteInterval.isNegative() || riskInterval.isZero() || riskInterval.isNegative()) {
            throw new IllegalArgumentException("vajra.live poll intervals must be positive");
        }
    }

    private static boolean isPlainOrigin(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return uri.getScheme() != null && uri.getHost() != null && uri.getRawPath().isEmpty()
                    && uri.getRawQuery() == null && uri.getRawFragment() == null && uri.getRawUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
