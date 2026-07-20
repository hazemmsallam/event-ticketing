package com.eventticketing.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI eventTicketingOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Event Ticketing API")
                .description("Event reservation system: browse events, pick seats from a hall's "
                        + "live map, hold seats, and confirm with payment.")
                .version("v1"));
    }
}
