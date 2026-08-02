package com.eventticketing.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A {@link Clock} that reports the database's notion of "now" rather than this JVM's.
 *
 * <p>Hold expiry is compared against {@code expires_at}, a value written by — and read back
 * through — the database. If each instance judged expiry by its own system clock, replicas with
 * skewed clocks would disagree about whether the same hold is still live: one releases the seats
 * while another still considers them held, and a customer can be told their hold expired while a
 * sibling instance is happily taking payment for it. Anchoring every instance to one authority
 * removes the class of bug entirely.
 *
 * <p>Rather than query the database on every call — expiry is checked on the hot booking path —
 * this measures the <em>offset</em> between database time and system time periodically and applies
 * it locally. Reads stay in-process; instances converge on the database's timeline.
 *
 * <p>The offset is sampled with a round-trip correction: the query's own latency is halved and
 * removed, so a slow sample does not bias the result forward.
 */
public class DatabaseAlignedClock extends Clock {

    private static final Logger log = LoggerFactory.getLogger(DatabaseAlignedClock.class);

    /** Beyond this the sample is treated as noise rather than a real offset. */
    private static final Duration SUSPICIOUS_OFFSET = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final ZoneId zone;
    private volatile Duration offset = Duration.ZERO;

    public DatabaseAlignedClock(JdbcTemplate jdbc) {
        this(jdbc, ZoneId.of("UTC"));
    }

    private DatabaseAlignedClock(JdbcTemplate jdbc, ZoneId zone) {
        this.jdbc = jdbc;
        this.zone = zone;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return new DatabaseAlignedClock(jdbc, other);
    }

    /**
     * Re-measures the drift between this JVM and the database. Runs on startup and then on a slow
     * cadence — clock drift is gradual, and a failed sample simply keeps the previous offset.
     */
    @Scheduled(initialDelay = 0, fixedDelay = 60_000)
    public void resync() {
        try {
            long startNanos = System.nanoTime();
            Timestamp dbNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
            long roundTripNanos = System.nanoTime() - startNanos;
            if (dbNow == null) {
                return;
            }
            // The reading was taken somewhere inside the round trip; assume the midpoint.
            Instant localMidpoint = Instant.now().minusNanos(roundTripNanos / 2);
            Duration sampled = Duration.between(localMidpoint, dbNow.toInstant());

            if (sampled.abs().compareTo(SUSPICIOUS_OFFSET) > 0) {
                log.warn("Database clock differs from this instance by {}s — check NTP on both. "
                        + "Keeping previous offset of {}s.", sampled.getSeconds(), offset.getSeconds());
                return;
            }
            if (sampled.minus(offset).abs().compareTo(Duration.ofSeconds(1)) > 0) {
                log.info("Clock offset to database adjusted from {}ms to {}ms.",
                        offset.toMillis(), sampled.toMillis());
            }
            offset = sampled;
        } catch (RuntimeException ex) {
            log.warn("Could not sample database time, keeping offset {}ms: {}",
                    offset.toMillis(), ex.getMessage());
        }
    }
}
