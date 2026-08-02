package com.eventticketing.common.security;

import com.eventticketing.common.exception.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Per-subject counter backed by Redis, so a limit holds across every application instance rather
 * than per-JVM.
 *
 * <p>Fixed-window counter: one key per (action, subject, window), incremented on each attempt and
 * expired when the window closes. {@code INCR} is atomic, so concurrent requests from the same
 * subject cannot both read a stale count.
 *
 * <p>{@link #reset} exists so a counter can be cleared by <em>good</em> behaviour rather than only
 * by the clock — see {@link UnpaidHoldThrottle}, where paying for a hold wipes the tally.
 *
 * <p><strong>Fails open.</strong> If Redis is unavailable the request is allowed and a warning is
 * logged. Failing closed would turn a cache outage into a total booking outage; this is an abuse
 * control, not a correctness control, and the database still guards inventory.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Counts one attempt and rejects once {@code limit} is exceeded inside {@code window}.
     *
     * @param action  short key segment naming the protected operation
     * @param subject the thing being limited — must be server-derived, never client-chosen
     * @throws TooManyRequestsException carrying the seconds left in the current window
     */
    public void check(String action, String subject, int limit, Duration window) {
        long windowSeconds = Math.max(1, window.getSeconds());
        long now = Instant.now().getEpochSecond();
        long windowStart = now - (now % windowSeconds);
        String key = key(action, subject, windowStart);

        Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // First hit in this window sets the expiry, so the key cleans itself up.
                redis.expire(key, Duration.ofSeconds(windowSeconds));
            }
        } catch (RuntimeException ex) {
            log.warn("Rate limiter unavailable ({}), allowing request: {}", key, ex.getMessage());
            return;
        }

        if (count != null && count > limit) {
            long retryAfter = windowStart + windowSeconds - now;
            throw new TooManyRequestsException(
                    "Too many requests. Try again in " + Math.max(1, retryAfter) + "s.", retryAfter);
        }
    }

    /** Clears the subject's tally for the current window. */
    public void reset(String action, String subject, Duration window) {
        long windowSeconds = Math.max(1, window.getSeconds());
        long now = Instant.now().getEpochSecond();
        try {
            redis.delete(key(action, subject, now - (now % windowSeconds)));
        } catch (RuntimeException ex) {
            log.warn("Could not reset rate limit for {}: {}", subject, ex.getMessage());
        }
    }

    private String key(String action, String subject, long windowStart) {
        return "rl:" + action + ":" + subject + ":" + windowStart;
    }
}
