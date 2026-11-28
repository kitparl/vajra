package com.vajra.gatewaybff.upstream;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code vajra.upstream.*}: where core and risk live. The risk token is a secret and is never logged. */
@ConfigurationProperties("vajra.upstream")
public record UpstreamProperties(
        String coreBaseUrl,
        String riskBaseUrl,
        String riskApiToken,
        @DefaultValue("PT3S") Duration timeout) {

    public UpstreamProperties {
        if (coreBaseUrl == null || coreBaseUrl.isBlank()) {
            throw new IllegalArgumentException("vajra.upstream.core-base-url (CORE_BASE_URL) must be set");
        }
        if (riskBaseUrl == null || riskBaseUrl.isBlank()) {
            throw new IllegalArgumentException("vajra.upstream.risk-base-url (RISK_BASE_URL) must be set");
        }
        if (riskApiToken == null || riskApiToken.isBlank()) {
            throw new IllegalArgumentException("vajra.upstream.risk-api-token (RISK_API_TOKEN) must be set");
        }
    }

    @Override
    public String toString() {
        return "UpstreamProperties[coreBaseUrl=" + coreBaseUrl + ", riskBaseUrl=" + riskBaseUrl + ", timeout="
                + timeout + "]";
    }
}
