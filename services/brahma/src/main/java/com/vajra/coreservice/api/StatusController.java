package com.vajra.coreservice.api;

import com.vajra.coreservice.oms.TradingMode;
import com.vajra.coreservice.oms.TradingProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatusController {

    record StatusView(TradingMode tradingMode) {}

    private final TradingProperties trading;

    StatusController(TradingProperties trading) {
        this.trading = trading;
    }

    @GetMapping("/api/v1/status")
    StatusView status() {
        return new StatusView(trading.mode());
    }
}
