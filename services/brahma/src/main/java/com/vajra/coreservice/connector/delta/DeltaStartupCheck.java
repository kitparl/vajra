package com.vajra.coreservice.connector.delta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** With TRADING_MODE=DELTA_TESTNET, fails fast on missing credentials, a bad key or clock skew. */
@Component
@ConditionalOnProperty(name = "vajra.trading.mode", havingValue = "DELTA_TESTNET")
class DeltaStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DeltaStartupCheck.class);

    private final DeltaCredentials credentials;
    private final DeltaPrivateClient client;

    DeltaStartupCheck(DeltaCredentials credentials, DeltaPrivateClient client) {
        this.credentials = credentials;
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!credentials.isPresent()) {
            throw new IllegalStateException("DELTA_TESTNET_API_KEY/SECRET required for TRADING_MODE=DELTA_TESTNET");
        }
        try {
            client.authCheck();
        } catch (DeltaApiException e) {
            if ("SignatureExpired".equals(e.code())) {
                throw new IllegalStateException("Delta rejected the signature timestamp: clock skew, check NTP on this host");
            }
            if ("invalid_api_key".equals(e.code())) {
                throw new IllegalStateException("invalid testnet API key");
            }
            log.warn("Delta auth check failed ({}), continuing: {}", e.code(), e.getMessage());
        }
    }
}
