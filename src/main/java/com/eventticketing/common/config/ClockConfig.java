package com.eventticketing.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** A single injectable clock so time-dependent logic (holds, expiry) is testable. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
