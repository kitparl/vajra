package com.vajra.gatewaybff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** gateway-bff: UI API, WebSocket gateway, notifier, scheduler. */
@ConfigurationPropertiesScan
@SpringBootApplication
public class GatewayBffApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayBffApplication.class, args);
    }
}
