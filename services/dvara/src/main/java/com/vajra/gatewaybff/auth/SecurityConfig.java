package com.vajra.gatewaybff.auth;

import static org.springframework.security.config.Customizer.withDefaults;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * Every path needs a session except the Docker healthcheck and the two pre-login endpoints. Login is a JSON
 * endpoint ({@link AuthController}), not formLogin. CSRF uses a double-submit cookie that the Angular app echoes
 * in the {@code X-XSRF-TOKEN} header.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    static final int BCRYPT_COST = 12;

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, CsrfTokenRepository csrfTokenRepository, SecurityContextRepository contextRepository,
            Clock clock) throws Exception {
        http.csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
                .authorizeHttpRequests(requests -> requests
                        // sendError (403, 404, 500) forwards to /error; without this the real status turns into a 401
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/api/v1/auth/csrf", "/api/v1/auth/login")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(unauthorizedJson())
                        .accessDeniedHandler(new AccessDeniedHandlerImpl()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(withDefaults())
                .addFilterBefore(new AbsoluteSessionLimitFilter(clock), SecurityContextHolderFilter.class);
        return http.build();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository(AuthProperties properties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.secure(properties.cookieSecure())
                .sameSite("Strict")
                .path("/"));
        return repository;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }

    @Bean
    UserDetailsService userDetailsService(AuthProperties properties) {
        return new InMemoryUserDetailsManager(User.withUsername(properties.username())
                .password(properties.passwordHash())
                .roles("ADMIN")
                .build());
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    private static AuthenticationEntryPoint unauthorizedJson() {
        return (HttpServletRequest request, HttpServletResponse response, org.springframework.security.core.AuthenticationException e) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"UNAUTHENTICATED\"}");
        };
    }
}
