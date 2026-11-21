package com.vajra.brahma.connector.delta;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** After an HTTP 429, blocks every Delta call until the reset time Delta gave us. */
public class DeltaRateLimitGuard {

    private final Clock clock;
    private volatile Instant pausedUntil = Instant.MIN;

    public DeltaRateLimitGuard(Clock clock) {
        this.clock = clock;
    }

    public void checkAllowed() {
        Instant until = pausedUntil;
        if (clock.instant().isBefore(until)) {
            throw new DeltaApiException("Delta calls paused by rate limit until " + until, true, null);
        }
    }

    public void pauseFor(Duration duration) {
        pausedUntil = clock.instant().plus(duration);
    }
}
