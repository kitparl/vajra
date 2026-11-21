package com.vajra.coreservice.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /internal/**} needs {@code Authorization: Bearer <CORE_INTERNAL_TOKEN>}; other paths are not filtered. Decides
 * on the servlet path, which Tomcat has already decoded and normalised (path parameters removed), so
 * {@code /internal;x=y/...} and {@code /%69nternal/...} cannot slip past.
 */
@Component
class InternalTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";
    private static final String INTERNAL = "/internal";

    private final byte[] expected;

    InternalTokenFilter(InternalProperties properties) {
        this.expected = properties.token().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !(path.equals(INTERNAL) || path.startsWith(INTERNAL + "/"));
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
