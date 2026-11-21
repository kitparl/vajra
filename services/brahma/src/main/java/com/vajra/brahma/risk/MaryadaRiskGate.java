package com.vajra.brahma.risk;

import com.vajra.brahma.oms.Order;
import com.vajra.brahma.position.Position;
import com.vajra.brahma.position.PositionRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Asks risk-service (maryada) to approve each order over REST. Fail closed (CLAUDE.md rule 3): an error, timeout,
 * non-200 status or incomplete answer rejects the order with {@code RISK_CHECK_FAILED}. Uses its own wire DTOs; brahma
 * never depends on maryada's classes (rule 1).
 */
public class MaryadaRiskGate implements RiskGate {

    private static final Logger log = LoggerFactory.getLogger(MaryadaRiskGate.class);

    /** Body of {@code POST /api/v1/approvals}. */
    record ApprovalRequest(String clientOrderId, String venue, String symbol, String side, long size, String orderType,
            BigDecimal limitPrice, boolean reduceOnly, String source, long currentPosition) {}

    /** maryada's answer; {@code decision} is APPROVED or REJECTED. */
    record ApprovalAnswer(UUID decisionId, String decision, String reason) {}

    private final RestClient http;
    private final PositionRepository positions;

    public MaryadaRiskGate(RestClient.Builder builder, String baseUrl, String apiToken, PositionRepository positions) {
        this.http = builder.baseUrl(baseUrl).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken).build();
        this.positions = positions;
    }

    @Override
    public RiskDecision approve(Order order) {
        try {
            ResponseEntity<ApprovalAnswer> response = http.post()
                    .uri("/api/v1/approvals")
                    .body(request(order))
                    .retrieve()
                    .toEntity(ApprovalAnswer.class);
            return decide(order, response);
        } catch (RuntimeException e) {
            log.warn("Risk check for order {} failed ({}); rejecting", order.id(), e.toString());
            return RiskDecision.reject(RiskDecision.RISK_CHECK_FAILED);
        }
    }

    private ApprovalRequest request(Order order) {
        long currentPosition = positions.find(order.venue(), order.instrumentId()).map(Position::size).orElse(0L);
        return new ApprovalRequest(order.clientOrderId(), order.venue().name(), order.symbol(), order.side().name(),
                order.size(), order.type().name(), order.limitPrice(), order.reduceOnly(), order.source().name(),
                currentPosition);
    }

    private static RiskDecision decide(Order order, ResponseEntity<ApprovalAnswer> response) {
        ApprovalAnswer answer = response.getBody();
        if (!response.getStatusCode().isSameCodeAs(HttpStatus.OK) || answer == null || answer.decisionId() == null) {
            return unusable(order, "status " + response.getStatusCode().value() + ", body " + answer);
        }
        return switch (String.valueOf(answer.decision())) {
            case "APPROVED" -> new RiskDecision(true, null, answer.decisionId());
            case "REJECTED" -> new RiskDecision(false, answer.reason(), answer.decisionId());
            default -> unusable(order, "unknown decision " + answer.decision());
        };
    }

    private static RiskDecision unusable(Order order, String what) {
        log.warn("Risk check for order {} got an unusable answer ({}); rejecting", order.id(), what);
        return RiskDecision.reject(RiskDecision.RISK_CHECK_FAILED);
    }
}
