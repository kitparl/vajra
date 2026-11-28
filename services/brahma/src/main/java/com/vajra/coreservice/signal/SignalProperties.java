package com.vajra.coreservice.signal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code vajra.signals.*}. A signal older than {@code maxAge} is not traded: it is recorded as a rejected order only.
 * The producer expires signals after 4 minutes; 5 minutes leaves room for clock skew and one slow poll.
 */
@ConfigurationProperties("vajra.signals")
record SignalProperties(@DefaultValue("PT5M") Duration maxAge) {

    SignalProperties {
        if (maxAge == null || maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("vajra.signals.max-age must be positive: " + maxAge);
        }
    }
}
