package com.vajra.coreservice.api;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code vajra.internal.*}: the token risk-service sends on {@code /internal/**}. Startup fails if it is blank. */
@Validated
@ConfigurationProperties("vajra.internal")
public record InternalProperties(@NotBlank String token) {}
