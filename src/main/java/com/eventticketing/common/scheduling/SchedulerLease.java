package com.eventticketing.common.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * A short-lived, cluster-wide lease so a scheduled job runs on one instance per tick instead of
 * on all of them.
 *
 * <p>Implemented as {@code SET key owner NX PX ttl} — atomic in Redis, so exactly one instance can
 * hold a given name at a time. The lease is deliberately <em>not</em> renewed or released early:
 * it simply expires. A worker that dies mid-run therefore blocks the job only until the TTL
 * lapses, and no cleanup path can leave a permanent lock behind.
 *
 * <p>Set the TTL slightly below the schedule interval so the next tick can always acquire it, and
 * comfortably above a normal run so two instances never overlap.
 *
 * <p><strong>Fails open.</strong> If Redis is unreachable the job runs anyway. Duplicate work is
 * wasteful but not incorrect: the sweeper is idempotent, and the reconciler claims each payment in
 * the database before touching the provider. Failing closed would stop refunds and hold cleanup
 * during a cache outage, which is the worse outcome.
 */
@Component
public class SchedulerLease {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLease.class);

    /** Identifies this instance, so a lease can be attributed when debugging. */
    private final String instanceId = UUID.randomUUID().toString();
    private final StringRedisTemplate redis;

    public SchedulerLease(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return true when this instance may run the job for the current tick
     */
    public boolean acquire(String jobName, Duration ttl) {
        try {
            Boolean won = redis.opsForValue()
                    .setIfAbsent("lease:" + jobName, instanceId, ttl);
            return Boolean.TRUE.equals(won);
        } catch (RuntimeException ex) {
            log.warn("Scheduler lease unavailable for '{}', running anyway: {}", jobName, ex.getMessage());
            return true;
        }
    }
}
