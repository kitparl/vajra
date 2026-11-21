package com.vajra.coreservice.connector.delta;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** One product from Delta {@code GET /v2/products}. Decimals arrive as JSON strings or numbers. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeltaProduct(
        @JsonProperty("id") long id,
        @JsonProperty("symbol") String symbol,
        @JsonProperty("contract_type") String contractType,
        @JsonProperty("state") String state,
        @JsonProperty("contract_value") BigDecimal contractValue,
        @JsonProperty("tick_size") BigDecimal tickSize,
        @JsonProperty("maker_commission_rate") BigDecimal makerCommissionRate,
        @JsonProperty("taker_commission_rate") BigDecimal takerCommissionRate) {}
