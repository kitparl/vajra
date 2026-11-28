package com.vajra.gatewaybff.upstream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class UpstreamPropertiesTest {

    @Test
    void blankOrMissingRiskTokenFailsFast() {
        for (String token : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> new UpstreamProperties("http://core", "http://risk", token, Duration.ofSeconds(3)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("RISK_API_TOKEN");
        }
    }
}
