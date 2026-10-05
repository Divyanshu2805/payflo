package com.project.payflo.merchant_service.security;

import com.project.payflo.common_lib.exception.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Locks an account's login for a while after too many wrong passwords, so a password can't be guessed
 * at the rate bcrypt allows. It counts per email, registered or not, so the lock never reveals whether
 * an account exists. Redis trouble never blocks a login.
 */
@Slf4j
@Component
public class LoginAttemptTracker {

    private static final String PREFIX = "login:failed:";

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final Duration window;

    public LoginAttemptTracker(StringRedisTemplate redis,
                               @Value("${app.login.max-failures:10}") int maxFailures,
                               @Value("${app.login.lock-minutes:15}") int lockMinutes) {
        this.redis = redis;
        this.maxFailures = maxFailures;
        this.window = Duration.ofMinutes(lockMinutes);
    }

    /** @throws RateLimitException while the account is locked */
    public void requireNotLocked(String email) {
        try {
            String count = redis.opsForValue().get(key(email));
            if (count != null && Long.parseLong(count) >= maxFailures) {
                Long ttl = redis.getExpire(key(email));
                throw new RateLimitException("Too many failed login attempts, try again later",
                        ttl != null && ttl > 0 ? ttl.intValue() : (int) window.toSeconds());
            }
        } catch (RateLimitException locked) {
            throw locked;
        } catch (Exception e) {
            log.warn("Login attempt check unavailable, allowing the attempt", e);
        }
    }

    public void recordFailure(String email) {
        try {
            Long count = redis.opsForValue().increment(key(email));
            if (count != null && count == 1) {
                redis.expire(key(email), window);
            }
        } catch (Exception e) {
            log.warn("Could not record a failed login", e);
        }
    }

    public void clear(String email) {
        try {
            redis.delete(key(email));
        } catch (Exception e) {
            log.warn("Could not clear failed logins", e);
        }
    }

    private static String key(String email) {
        return PREFIX + email.trim().toLowerCase(Locale.ROOT);
    }
}
