package com.vajra.brahma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** core-service: connectors, market data, OMS, positions, audit. */
@SpringBootApplication
public class BrahmaApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrahmaApplication.class, args);
    }
}
