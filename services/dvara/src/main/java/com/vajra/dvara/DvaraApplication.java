package com.vajra.dvara;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** gateway-bff: UI API, WebSocket gateway, notifier, scheduler. */
@SpringBootApplication
public class DvaraApplication {

    public static void main(String[] args) {
        SpringApplication.run(DvaraApplication.class, args);
    }
}
