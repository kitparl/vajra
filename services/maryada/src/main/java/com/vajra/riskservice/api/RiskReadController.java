package com.vajra.riskservice.api;

import com.vajra.riskservice.risk.RiskDecisionRepository;
import com.vajra.riskservice.risk.RiskDecisionRepository.Rejection;
import com.vajra.riskservice.risk.RiskLimitRepository;
import com.vajra.riskservice.risk.RiskLimitRepository.RiskLimitRow;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only views for the terminal; no part of the decision path. */
@RestController
@RequestMapping("/api/v1")
class RiskReadController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final RiskLimitRepository limits;
    private final RiskDecisionRepository decisions;

    RiskReadController(RiskLimitRepository limits, RiskDecisionRepository decisions) {
        this.limits = limits;
        this.decisions = decisions;
    }

    @GetMapping("/limits")
    List<RiskLimitRow> limits() {
        return limits.listAll();
    }

    @GetMapping("/rejections")
    List<Rejection> rejections(@RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("limit must be between 1 and " + MAX_LIMIT);
        }
        return decisions.recentRejections(limit);
    }
}
