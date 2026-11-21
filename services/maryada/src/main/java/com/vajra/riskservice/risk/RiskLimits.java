package com.vajra.riskservice.risk;

public record RiskLimits(long maxOrderSize, long maxAbsPosition, long maxOrdersPerMinute) {}
