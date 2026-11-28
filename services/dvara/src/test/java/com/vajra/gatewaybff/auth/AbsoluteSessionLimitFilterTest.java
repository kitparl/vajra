package com.vajra.gatewaybff.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class AbsoluteSessionLimitFilterTest {

    private static final Instant CREATED = Instant.parse("2026-11-28T10:00:00Z");

    @Test
    void sessionEndsAtEightHoursNotBefore() throws Exception {
        assertThat(sessionValidAfter(Duration.ofHours(8).minusSeconds(1))).isTrue();
        assertThat(sessionValidAfter(Duration.ofHours(8))).isFalse();
    }

    private boolean sessionValidAfter(Duration age) throws Exception {
        MockHttpSession session = new MockHttpSession() {
            @Override
            public long getCreationTime() {
                return CREATED.toEpochMilli();
            }
        };
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        new AbsoluteSessionLimitFilter(Clock.fixed(CREATED.plus(age), ZoneOffset.UTC))
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return !session.isInvalid();
    }
}
