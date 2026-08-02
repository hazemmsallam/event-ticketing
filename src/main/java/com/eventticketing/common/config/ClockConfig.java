package com.eventticketing.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /**
     * A single injectable clock so time-dependent logic (holds, expiry) is testable — and, in a
     * multi-instance deployment, so every replica agrees what "now" is.
     *
     * <p>Hold expiry is judged against timestamps the database owns, so the database is the
     * authority on time. Using each JVM's own clock would let skewed replicas disagree about
     * whether the same hold is still live. Every existing {@code clock.instant()} call site picks
     * this up unchanged.
     */
    @Bean
    public Clock clock(JdbcTemplate jdbcTemplate) {
        return new DatabaseAlignedClock(jdbcTemplate);
    }
}
