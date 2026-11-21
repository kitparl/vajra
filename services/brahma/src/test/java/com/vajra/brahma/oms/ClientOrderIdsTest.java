package com.vajra.brahma.oms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientOrderIdsTest {

    @Test
    void isDeterministicAnd32Chars() {
        String id = ClientOrderIds.fromKey("abc");
        assertThat(ClientOrderIds.fromKey("abc")).isEqualTo(id);
        assertThat(id).hasSize(32).startsWith("v").isEqualTo("vba7816bf8f01cfea414140de5dae222");
    }

    @Test
    void differentKeysDiffer() {
        assertThat(ClientOrderIds.fromKey("abc")).isNotEqualTo(ClientOrderIds.fromKey("abd"));
    }
}
