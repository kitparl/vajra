package com.vajra.gatewaybff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** gateway-bff: UI API, WebSocket gateway, notifier, scheduler. */
@SpringBootApplication
public class GatewayBffApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayBffApplication.class, args);
    }
}
