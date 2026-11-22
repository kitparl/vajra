package com.vajra.coreservice.signal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Boot cannot express the back-off in properties; it picks up this handler for the listener container. */
@Configuration
class KafkaConsumerConfig {

    private static final long RETRY_INTERVAL_MS = 2000;
    private static final long RETRY_ATTEMPTS = 5;

    /** Retries an unexpected failure 5 times, 2 s apart; then logs it at ERROR and skips the record. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(RETRY_INTERVAL_MS, RETRY_ATTEMPTS));
    }
}
