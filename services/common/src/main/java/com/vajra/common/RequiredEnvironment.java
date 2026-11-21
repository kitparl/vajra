package com.vajra.common;

import java.util.List;
import org.springframework.core.env.Environment;

/**
 * Fails startup with a clear message when required environment variables are missing.
 * Spring otherwise keeps an unresolved "${DB_PASSWORD}" literal and fails later with an unrelated error.
 */
public final class RequiredEnvironment {

    static final List<String> REQUIRED = List.of("DB_URL", "DB_USER", "DB_PASSWORD");

    private RequiredEnvironment() {}

    public static void check(Environment environment) {
        List<String> missing = REQUIRED.stream()
                .filter(name -> {
                    String value = environment.getProperty(name);
                    return value == null || value.isBlank();
                })
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required environment variable(s): " + String.join(", ", missing));
        }
    }
}
