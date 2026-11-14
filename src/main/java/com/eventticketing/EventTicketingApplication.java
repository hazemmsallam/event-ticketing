package com.eventticketing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EventTicketingApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventTicketingApplication.class, args);
    }
}
