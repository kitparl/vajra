package com.vajra.gatewaybff.live;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Lets one warning through per window; one instance per poller. */
class WarnThrottle {

    private final Clock clock;
    private final long windowMillis;
    private final AtomicLong lastMillis = new AtomicLong(Long.MIN_VALUE);

    WarnThrottle(Clock clock, Duration window) {
        this.clock = clock;
        this.windowMillis = window.toMillis();
    }

    boolean tryAcquire() {
        long now = clock.millis();
        long last = lastMillis.get();
        return (last == Long.MIN_VALUE || now - last >= windowMillis) && lastMillis.compareAndSet(last, now);
    }
}
