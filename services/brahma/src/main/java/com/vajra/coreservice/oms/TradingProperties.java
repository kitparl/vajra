package com.vajra.coreservice.oms;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code vajra.trading.*}: live trading is off by default (CLAUDE.md rule 4). */
@ConfigurationProperties("vajra.trading")
public record TradingProperties(@DefaultValue("PAPER") TradingMode mode) {}
