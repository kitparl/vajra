package com.vajra.maryada.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Deny by default: every request needs {@code Authorization: Bearer <RISK_API_TOKEN>} except the health probe. */
@Component
class BearerTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";
    private static final String HEALTH_PATH = "/actuator/health";

    private final byte[] expected;

    BearerTokenFilter(SecurityProperties properties) {
        this.expected = properties.riskApiToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HEALTH_PATH.equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())
                && MessageDigest.isEqual(header.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8), expected)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }
}
