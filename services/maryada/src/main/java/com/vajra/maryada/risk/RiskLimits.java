package com.vajra.maryada.risk;

public record RiskLimits(long maxOrderSize, long maxAbsPosition, long maxOrdersPerMinute) {}
