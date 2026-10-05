package com.project.payflo.merchant_service.security;

import com.project.payflo.common_lib.util.RandomizerUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Date;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A dashboard user's sessions, kept in Redis.
 *
 * <p><b>Refresh tokens</b> are random, single use, and stored only as a hash: exchanging one returns a new pair
 * and invalidates it. <b>Revocation</b> is what the gateway reads (see its {@code JwtAuthHandler}) to refuse an
 * access token before it expires: one logged-out token by its id, or everything a user was issued before a
 * given moment (after a password change, a role change, or removal). The key names below are shared with it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionService {

    private static final String REFRESH_PREFIX = "refresh:";
    private static final String REFRESH_BY_USER_PREFIX = "refresh:user:";
    // Shared with the gateway:
    private static final String REVOKED_TOKEN_PREFIX = "jwt:revoked:";
    private static final String USER_REVOKED_AFTER_PREFIX = "jwt:user-revoked-after:";

    private final StringRedisTemplate redis;

    @Value("${jwt.refresh-token-expiry-days:7}")
    private int refreshTokenDays;

    /** A new refresh token for the user, or null if Redis is unavailable (the login still succeeds without one). */
    public String issueRefreshToken(String email) {
        try {
            String raw = RandomizerUtil.randomBase64(32);
            String hash = hash(raw);
            Duration ttl = Duration.ofDays(refreshTokenDays);
            redis.opsForValue().set(REFRESH_PREFIX + hash, normalize(email), ttl);
            redis.opsForSet().add(REFRESH_BY_USER_PREFIX + normalize(email), hash);
            redis.expire(REFRESH_BY_USER_PREFIX + normalize(email), ttl);
            return raw;
        } catch (Exception e) {
            log.warn("Could not issue a refresh token", e);
            return null;
        }
    }

    /** Uses up a refresh token: the user it belonged to, or empty if it is unknown, expired or already used. */
    public Optional<String> consumeRefreshToken(String raw) {
        String hash = hash(raw);
        String email = redis.opsForValue().getAndDelete(REFRESH_PREFIX + hash);
        if (email == null) {
            return Optional.empty();
        }
        redis.opsForSet().remove(REFRESH_BY_USER_PREFIX + email, hash);
        return Optional.of(email);
    }

    public void revokeRefreshToken(String raw) {
        consumeRefreshToken(raw);
    }

    /** Logout of one access token, until it would have expired anyway. */
    public void revokeAccessToken(String tokenId, Date expiresAt) {
        if (tokenId == null) {
            return;
        }
        long seconds = Math.max(1, (expiresAt.getTime() - System.currentTimeMillis()) / 1000 + 1);
        redis.opsForValue().set(REVOKED_TOKEN_PREFIX + tokenId, "1", Duration.ofSeconds(seconds));
    }

    /**
     * Ends every session of a user: refresh tokens are deleted and every access token issued up to now is
     * refused. A change of password, role or membership is what calls this.
     */
    public void revokeAllSessions(String email) {
        String user = normalize(email);
        redis.opsForValue().set(USER_REVOKED_AFTER_PREFIX + user, String.valueOf(System.currentTimeMillis()),
                Duration.ofSeconds(JwtUtil.ACCESS_TOKEN_SECONDS + 60));

        Set<String> hashes = redis.opsForSet().members(REFRESH_BY_USER_PREFIX + user);
        if (hashes != null) {
            hashes.forEach(h -> redis.delete(REFRESH_PREFIX + h));
        }
        redis.delete(REFRESH_BY_USER_PREFIX + user);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
