package com.vajra.gatewaybff.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class LivePropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "http://ui.test/", "http://ui.test/app", "ui.test", "http://ui.test?x=1"})
    void badOriginFailsFast(String origin) {
        assertThatThrownBy(() -> new LiveProperties(origin, Duration.ofSeconds(1), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vajra.live.ui-origin");
    }

    @Test
    void plainOriginIsAccepted() {
        assertThat(new LiveProperties("https://box.tail1234.ts.net", Duration.ofSeconds(1), Duration.ofSeconds(2))
                .uiOrigin()).isEqualTo("https://box.tail1234.ts.net");
        assertThat(new LiveProperties("http://localhost:8088", Duration.ofSeconds(1), Duration.ofSeconds(2))
                .uiOrigin()).isEqualTo("http://localhost:8088");
    }
}
