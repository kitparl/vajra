package com.vajra.coreservice.marketdata;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code vajra.marketdata.*}: symbols that get quotes, candles and trading. */
@ConfigurationProperties("vajra.marketdata")
public record MarketDataProperties(@DefaultValue({"BTCUSD", "ETHUSD"}) Set<String> symbols) {}
