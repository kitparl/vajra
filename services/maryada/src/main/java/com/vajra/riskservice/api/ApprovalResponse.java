package com.vajra.riskservice.api;

import java.util.UUID;

/** {@code decision}: APPROVED or REJECTED; {@code reason} is null when approved. */
public record ApprovalResponse(UUID decisionId, String decision, String reason) {}
