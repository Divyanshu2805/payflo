package com.project.payflo.common_lib.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Counters over a window, for velocity rules: how many cards were tokenized, how many card payments were tried and how
 * many of them failed, in the last few minutes. Each key is a Redis hash of named counts that all expire together when the
 * window that began with the first count ends — so two counts that are compared (failures against attempts) always cover the
 * same stretch of time, which two separate keys expiring at different moments wouldn't.
 *
 * <p>Like the rate limiters, it fails open: if Redis can't be reached nothing is counted and nothing is refused, so an
 * outage of the counters never stops a merchant taking payments.
 */
@Slf4j
public class VelocityCounters {

    private final StringRedisTemplate redis;

    public VelocityCounters(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Adds one to {@code field} of {@code key} and returns the new count; the window starts with the first count under the key. */
    public long increment(String key, String field, Duration window) {
        try {
            Long count = redis.opsForHash().increment(key, field, 1);
            // Set on the first count, and again if a crash between the two calls left the key without one.
            Long ttl = redis.getExpire(key);
            if (ttl == null || ttl < 0) {
                redis.expire(key, window);
            }
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("Velocity counter unavailable, not counting {} {}", key, field, e);
            return 0;
        }
    }

    /** Every count under the key (empty when there are none, or Redis can't be reached). */
    public Map<String, Long> counts(String key) {
        Map<String, Long> counts = new HashMap<>();
        try {
            redis.opsForHash().entries(key).forEach((field, value) -> counts.put(field.toString(), Long.parseLong(value.toString())));
        } catch (Exception e) {
            log.warn("Velocity counter unavailable, reading nothing for {}", key, e);
            counts.clear();
        }
        return counts;
    }

    /** How long until the window under the key ends, at least one second. */
    public long secondsLeft(String key) {
        try {
            Long ttl = redis.getExpire(key);
            return ttl != null && ttl > 0 ? ttl : 1;
        } catch (Exception e) {
            return 1;
        }
    }
}
