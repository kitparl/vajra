package com.vajra.gatewaybff.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.web.filter.OncePerRequestFilter;

/** Ends a session 8 hours after it was created, however active it is (the idle timeout is separate). */
class AbsoluteSessionLimitFilter extends OncePerRequestFilter {

    static final Duration MAX_AGE = Duration.ofHours(8);

    private final Clock clock;

    AbsoluteSessionLimitFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && clock.millis() - session.getCreationTime() >= MAX_AGE.toMillis()) {
            session.invalidate();
        }
        chain.doFilter(request, response);
    }
}
