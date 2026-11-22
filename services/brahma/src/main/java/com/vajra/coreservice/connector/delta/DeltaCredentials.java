package com.vajra.coreservice.connector.delta;

/**
 * Delta API key and secret from env. Both are trimmed; a value that still holds whitespace or control characters
 * makes {@link #isPresent()} false (it would break the HTTP header, and its text is never echoed).
 * {@code toString()} hides both so they cannot reach a log.
 */
public record DeltaCredentials(String apiKey, String apiSecret) {

    public DeltaCredentials {
        apiKey = apiKey == null ? null : apiKey.strip();
        apiSecret = apiSecret == null ? null : apiSecret.strip();
    }

    public boolean isPresent() {
        return usable(apiKey) && usable(apiSecret);
    }

    private static boolean usable(String value) {
        return value != null && !value.isEmpty() && value.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    @Override
    public String toString() {
        return "DeltaCredentials[apiKey=***, apiSecret=***]";
    }
}
