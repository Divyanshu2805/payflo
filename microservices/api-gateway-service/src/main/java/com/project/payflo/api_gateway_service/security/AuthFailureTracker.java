package com.project.payflo.api_gateway_service.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Counts failed authentications per client address, so guessing credentials gets harder the more is
 * tried. The per-key rate limit only applies after a key verifies; this is what limits the attempts
 * that don't. Redis trouble never blocks a request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthFailureTracker {

    private static final String PREFIX = "authfail:";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final SecurityRouteProperties properties;

    /** @return seconds the caller must wait, or 0 if it may try */
    public int blockedForSeconds(String clientIp) {
        try {
            String count = redis.opsForValue().get(PREFIX + clientIp);
            if (count != null && Long.parseLong(count) >= properties.getMaxFailedAuthPerMinute()) {
                Long ttl = redis.getExpire(PREFIX + clientIp);
                return ttl != null && ttl > 0 ? ttl.intValue() : (int) WINDOW.toSeconds();
            }
        } catch (Exception e) {
            log.warn("Auth failure check unavailable, allowing the attempt", e);
        }
        return 0;
    }

    public void recordFailure(String clientIp) {
        try {
            Long count = redis.opsForValue().increment(PREFIX + clientIp);
            if (count != null && count == 1) {
                redis.expire(PREFIX + clientIp, WINDOW);
            }
        } catch (Exception e) {
            log.warn("Could not record a failed authentication", e);
        }
    }
}
