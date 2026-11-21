package com.vajra.maryada.risk;

import com.vajra.maryada.api.ApprovalResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Approves or vetoes one order. Approvals are serialised by a transaction-scoped advisory lock. */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final long APPROVAL_LOCK_KEY = 4711;
    private static final Duration RATE_WINDOW = Duration.ofSeconds(60);

    private final JdbcClient jdbc;
    private final KillSwitchRepository killSwitch;
    private final RiskLimitRepository limits;
    private final RiskDecisionRepository decisions;
    private final Clock clock;

    public ApprovalService(JdbcClient jdbc, KillSwitchRepository killSwitch, RiskLimitRepository limits,
            RiskDecisionRepository decisions, Clock clock) {
        this.jdbc = jdbc;
        this.killSwitch = killSwitch;
        this.limits = limits;
        this.decisions = decisions;
        this.clock = clock;
    }

    /**
     * Any failure while deciding becomes a stored REJECTED (fail closed). If the database itself fails, the
     * exception propagates (5xx) and the caller treats it as a reject.
     */
    @Transactional
    public ApprovalResponse approve(ApprovalRequest request) {
        jdbc.sql("select pg_advisory_xact_lock(:key)").param("key", APPROVAL_LOCK_KEY).query().singleRow();
        Instant now = clock.instant();
        Verdict verdict;
        try {
            boolean halted = killSwitch.halted();
            RiskLimits current = limits.load();
            long approvedLastMinute = decisions.countApprovedSince(now.minus(RATE_WINDOW));
            verdict = RiskRules.evaluate(request, current, halted, approvedLastMinute);
        } catch (RuntimeException e) {
            log.error("risk evaluation failed for {}, rejecting", request.clientOrderId(), e);
            verdict = Verdict.reject("INTERNAL_ERROR");
        }
        UUID id = UUID.randomUUID();
        decisions.insert(id, request, verdict, now);
        return new ApprovalResponse(id, verdict.approved() ? "APPROVED" : "REJECTED", verdict.reason());
    }
}
