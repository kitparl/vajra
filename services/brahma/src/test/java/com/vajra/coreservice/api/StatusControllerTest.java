package com.vajra.coreservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vajra.coreservice.oms.TradingMode;
import com.vajra.coreservice.oms.TradingProperties;
import org.junit.jupiter.api.Test;

class StatusControllerTest {

    @Test
    void statusReturnsTradingMode() {
        StatusController controller = new StatusController(new TradingProperties(TradingMode.DELTA_TESTNET));

        assertThat(controller.status().tradingMode()).isEqualTo(TradingMode.DELTA_TESTNET);
    }
}
