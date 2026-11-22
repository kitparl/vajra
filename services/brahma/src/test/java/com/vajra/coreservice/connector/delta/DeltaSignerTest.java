package com.vajra.coreservice.connector.delta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeltaSignerTest {

    private static final String BODY = "{\"product_id\":27,\"size\":1,\"side\":\"buy\",\"order_type\":\"market_order\"}";

    @Test
    void knownVector() {
        // Expected hex computed once, independently, with: openssl dgst -sha256 -hmac test-secret
        String signature = DeltaSigner.sign("test-secret", "POST", 1700000000L, "/v2/orders", "", BODY);

        assertThat(signature).isEqualTo("c03519f159d74778adfae71120a847fd8b3f763de19660c57c2cbe731d8df723");
    }

    @Test
    void getWithQueryIncludesQuestionMark() {
        String withQuery = DeltaSigner.sign("test-secret", "GET", 1700000000L, "/v2/positions", "?product_id=27", "");
        String without = DeltaSigner.sign("test-secret", "GET", 1700000000L, "/v2/positions", "", "");

        assertThat(withQuery).isNotEqualTo(without).matches("[0-9a-f]{64}");
    }
}
