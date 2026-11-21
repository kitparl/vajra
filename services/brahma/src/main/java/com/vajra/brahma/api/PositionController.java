package com.vajra.brahma.api;

import com.vajra.brahma.position.PnlSummary;
import com.vajra.brahma.position.PositionService;
import com.vajra.brahma.position.PositionView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PositionController {

    private final PositionService positions;

    PositionController(PositionService positions) {
        this.positions = positions;
    }

    @GetMapping("/api/v1/positions")
    List<PositionView> positions() {
        return positions.views();
    }

    @GetMapping("/api/v1/pnl")
    PnlSummary pnl() {
        return positions.pnl();
    }
}
