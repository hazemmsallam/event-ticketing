package com.eventticketing.common.config;

import com.eventticketing.reservation.config.CacheNames;
import com.eventticketing.reservation.config.ReservationProperties;
import com.eventticketing.reservation.dto.EventAvailabilityResponse;
import com.eventticketing.reservation.dto.EventSeatMapResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter.TtlFunction;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis caching for the read-heavy availability endpoints. Each cache stores exactly one
 * response type, so a type-bound JSON serializer round-trips the records cleanly.
 *
 * <p>Caching is best-effort: if Redis is unreachable, {@link #errorHandler()} logs and
 * swallows the error so the request simply falls back to a database read. A stale cache entry
 * can never cause a double-booking — the database unique index still guards every write.
 *
 * <p>Entry lifetimes are <strong>jittered</strong>. With a fixed TTL, every entry created during a
 * burst expires in the same instant, and the next wave of readers all miss together and stampede
 * the database — the load pattern the cache exists to prevent, arriving on a timer. Spreading
 * expiry across a window desynchronises them.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    private final ReservationProperties properties;
    private final ObjectMapper objectMapper;

    public CacheConfig(ReservationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** How far either side of the configured TTL an entry's lifetime may land. */
    private static final double JITTER_FRACTION = 0.2;

    /**
     * The configured TTL ±20%, so entries written together do not expire together.
     * {@link ThreadLocalRandom} keeps this free of contention on the write path.
     */
    private Duration jitteredTtl() {
        long base = properties.cacheTtl().toMillis();
        long spread = (long) (base * JITTER_FRACTION);
        if (spread <= 0) {
            return properties.cacheTtl();
        }
        return Duration.ofMillis(base + ThreadLocalRandom.current().nextLong(-spread, spread + 1));
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl((TtlFunction) (key, value) -> jitteredTtl())
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .withCacheConfiguration(CacheNames.EVENT_SEAT_MAP,
                        base.serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(objectMapper, EventSeatMapResponse.class))))
                .withCacheConfiguration(CacheNames.EVENT_AVAILABILITY,
                        base.serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(objectMapper, EventAvailabilityResponse.class))))
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException ex, Cache cache, Object key) {
                log.warn("Cache GET failed ({}), falling back to source: {}", cache.getName(), ex.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException ex, Cache cache, Object key, Object value) {
                log.warn("Cache PUT failed ({}): {}", cache.getName(), ex.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException ex, Cache cache, Object key) {
                log.warn("Cache EVICT failed ({}): {}", cache.getName(), ex.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException ex, Cache cache) {
                log.warn("Cache CLEAR failed ({}): {}", cache.getName(), ex.getMessage());
            }
        };
    }
}
