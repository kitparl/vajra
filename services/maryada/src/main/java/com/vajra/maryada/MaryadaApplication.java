package com.vajra.maryada;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** risk-service: approves or vetoes every order, owns the kill-switch. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MaryadaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaryadaApplication.class, args);
    }
}
