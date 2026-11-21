package com.vajra.coreservice.risk;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code vajra.risk.*}: where risk-service lives and the token it expects. Startup fails if either is blank. */
@Validated
@ConfigurationProperties("vajra.risk")
public record RiskProperties(@NotBlank String baseUrl, @NotBlank String apiToken) {}
