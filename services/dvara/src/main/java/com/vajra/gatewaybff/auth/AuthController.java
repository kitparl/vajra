package com.vajra.gatewaybff.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Login, logout, CSRF bootstrap and "who am I" for the terminal UI. Every outcome is audited. */
@RestController
class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final int MAX_LOGGED_USERNAME = 64;

    private final ReentrantLock attempts = new ReentrantLock(true);
    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final LoginLockout lockout;
    private final AuditEventRepository audit;
    private final String tradingMode;

    AuthController(
            AuthenticationManager authenticationManager, SessionAuthenticationStrategy sessionStrategy,
            SecurityContextRepository contextRepository, CsrfTokenRepository csrfTokenRepository,
            LoginLockout lockout, AuditEventRepository audit,
            @Value("${vajra.trading-mode}") String tradingMode) {
        this.authenticationManager = authenticationManager;
        this.sessionStrategy = sessionStrategy;
        this.contextRepository = contextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.lockout = lockout;
        this.audit = audit;
        this.tradingMode = tradingMode;
    }

    /** Resolving the deferred token is what makes Spring Security write the XSRF-TOKEN cookie. */
    @GetMapping("/api/v1/auth/csrf")
    ResponseEntity<Void> csrf(CsrfToken token) {
        token.getToken();
        return ResponseEntity.noContent().build();
    }

    /**
     * One attempt at a time (fair lock): lock check, bcrypt verify and failure count form one step, so a burst of
     * parallel guesses cannot slip past the lockout or starve the CPU with bcrypt.
     */
    @PostMapping("/api/v1/auth/login")
    ResponseEntity<Map<String, ?>> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        attempts.lock();
        try {
            return attempt(body, request, response);
        } finally {
            attempts.unlock();
        }
    }

    private ResponseEntity<Map<String, ?>> attempt(
            LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        String username = loggable(body.username());
        if (lockout.isLocked()) {
            return locked(username);
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        } catch (AuthenticationException e) {
            lockout.recordFailure();
            audit.record(AuditEventRepository.LOGIN_FAILED, Map.of("username", username, "reason", "BAD_CREDENTIALS"));
            log.warn("Login failed for user '{}'", username);
            if (lockout.isLocked()) {
                audit.record(AuditEventRepository.LOGIN_LOCKED, Map.of("username", username));
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "BAD_CREDENTIALS"));
        }
        // Audit first: if it cannot be written, no session is established.
        audit.record(AuditEventRepository.LOGIN_OK, Map.of("username", username));
        lockout.recordSuccess();
        sessionStrategy.onAuthentication(authentication, request, response);
        // New session, new CSRF token: written now so the UI does not need another /csrf round trip.
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        log.info("Login ok for user '{}'", username);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Map<String, ?>> locked(String username) {
        long retryAfterSeconds = Math.max(1, (lockout.retryAfter().toMillis() + 999) / 1000);
        audit.record(AuditEventRepository.LOGIN_LOCKED, Map.of("username", username));
        log.warn("Login refused, locked for another {}s", retryAfterSeconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(Map.of("error", "LOCKED", "retryAfterSeconds", retryAfterSeconds));
    }

    /** Attacker-controlled text: no control characters (log/audit injection) and a short cap. */
    private static String loggable(String username) {
        String clean = username.replaceAll("\\p{Cntrl}", "");
        return clean.length() > MAX_LOGGED_USERNAME ? clean.substring(0, MAX_LOGGED_USERNAME) : clean;
    }

    @PostMapping("/api/v1/auth/logout")
    ResponseEntity<Void> logout(Authentication authentication, HttpServletRequest request) {
        try {
            audit.record(AuditEventRepository.LOGOUT, Map.of("username", authentication.getName()));
        } finally {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
        }
        log.info("Logout for user '{}'", authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/session")
    Map<String, String> session(Authentication authentication) {
        return Map.of("username", authentication.getName(), "tradingMode", tradingMode);
    }

    record LoginRequest(
            @NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 256) String password) {

        /** Never print the password, even by accident. */
        @Override
        public String toString() {
            return "LoginRequest[username=" + username + "]";
        }
    }
}
