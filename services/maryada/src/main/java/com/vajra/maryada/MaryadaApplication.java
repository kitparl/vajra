package com.vajra.maryada;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** risk-service: approves or vetoes every order, owns the kill-switch. */
@SpringBootApplication
public class MaryadaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaryadaApplication.class, args);
    }
}
