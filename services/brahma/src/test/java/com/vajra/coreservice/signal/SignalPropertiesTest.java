package com.vajra.coreservice.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SignalPropertiesTest {

    @Test
    void maxAgeMustBePositive() {
        assertThatThrownBy(() -> new SignalProperties(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SignalProperties(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void positiveMaxAgeIsAccepted() {
        assertThat(new SignalProperties(Duration.ofMinutes(5)).maxAge()).isEqualTo(Duration.ofMinutes(5));
    }
}
