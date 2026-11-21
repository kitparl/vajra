package com.vajra.brahma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** core-service: connectors, market data, OMS, positions, audit. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BrahmaApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrahmaApplication.class, args);
    }
}
