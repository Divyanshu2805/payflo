package com.project.payflo.common_lib.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Fixed window: at most {@code maxRequests} per window, counted from the first request in it.
 *
 * <p>The increment and the expiry run as one Lua script. As two separate commands, a crash between
 * them left a counter with no expiry, which locked that key out for good. The script also re-arms the
 * expiry if it finds a counter without one.
 */
@Slf4j
@RequiredArgsConstructor
public class FixedWindowRateLimiter implements RateLimiter {

    private static final RedisScript<List> SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 or redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
            end
            return {count, redis.call('TTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redis;

    @Override
    @SuppressWarnings("unchecked")
    public RateLimitResult check(String key, int maxRequestAllowed, long windowSeconds) {
        try {
            List<Long> result = redis.execute(SCRIPT, List.of("ratelimit:fixed:" + key), String.valueOf(windowSeconds));
            if (result == null || result.size() < 2) {
                return RateLimitResult.allowed(maxRequestAllowed);
            }

            long count = result.get(0);
            long ttl = result.get(1);
            if (count > maxRequestAllowed) {
                return RateLimitResult.denied(ttl > 0 ? (int) ttl : (int) windowSeconds);
            }
            return RateLimitResult.allowed((int) (maxRequestAllowed - count));
        } catch (DataAccessException e) {
            // Rate limiting is a safeguard, not the thing being protected: when Redis is down, keep serving.
            log.warn("Rate limiter unavailable, failing open for key={}", key, e);
            return RateLimitResult.allowed(maxRequestAllowed);
        }
    }
}
