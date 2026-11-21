package com.vajra.riskservice.api;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Service tokens. Startup fails if a token is missing or blank. */
@Validated
@ConfigurationProperties("vajra.security")
public record SecurityProperties(@NotBlank String riskApiToken, @NotBlank String coreInternalToken) {}
