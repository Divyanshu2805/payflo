package com.project.payflo.api_gateway_service.security;

import com.project.payflo.common_lib.exception.RateLimitException;
import com.project.payflo.common_lib.ratelimit.RateLimitResult;
import com.project.payflo.common_lib.ratelimit.RateLimiter;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthHandler {

    // Written by merchant-service: a token revoked by logout, and "everything this user was issued before now"
    // after a password change, role change or removal. Entries expire with the longest-lived token they cover.
    static final String REVOKED_TOKEN_PREFIX = "jwt:revoked:";
    static final String USER_REVOKED_AFTER_PREFIX = "jwt:user-revoked-after:";

    private final JwtVerifier jwtVerifier;
    private final RateLimiter rateLimiter;
    private final StringRedisTemplate redis;

    // Per merchant, since a JWT is a person at a dashboard, not an integration: far more generous than
    // an API key's limit, but not unlimited.
    @Value("${app.rate-limit.use-case.jwt.requests-per-minute:600}")
    private int requestsPerMinute;

    public Map<String, String> authenticate(String token) {
        Claims claims;
        try {
            claims = jwtVerifier.verify(token);
        } catch (Exception e) {
            throw new GatewayAuthenticationException("Invalid or expired token");
        }

        String merchantId = jwtVerifier.extractMerchantId(claims);
        String role = jwtVerifier.extractRole(claims);
        String email = jwtVerifier.extractEmail(claims);
        if (merchantId == null || role == null) {
            throw new GatewayAuthenticationException("Invalid or expired token");
        }

        if (isRevoked(claims, email)) {
            throw new GatewayAuthenticationException("Invalid or expired token");
        }

        RateLimitResult rateLimitResult = rateLimiter.check("jwt:" + merchantId, requestsPerMinute, 60);
        if (!rateLimitResult.isAllowed()) {
            throw new RateLimitException("Too many requests", rateLimitResult.retryAfterSeconds());
        }

        Map<String, String> identity = new HashMap<>();
        identity.put("X-Merchant-Id", merchantId);
        identity.put("X-User-Role", role);
        if (email != null) {
            identity.put("X-User-Email", email);
        }
        return identity;
    }

    // A token is refused if it was logged out, or issued before its user's sessions were cut off. If Redis
    // can't be asked the token is accepted: it is still signed and unexpired, and an outage shouldn't lock
    // every dashboard user out.
    private boolean isRevoked(Claims claims, String email) {
        try {
            String tokenId = jwtVerifier.extractTokenId(claims);
            List<String> values = redis.opsForValue().multiGet(List.of(
                    REVOKED_TOKEN_PREFIX + (tokenId != null ? tokenId : "none"),
                    USER_REVOKED_AFTER_PREFIX + (email != null ? email.toLowerCase() : "none")));
            if (values == null) {
                return false;
            }
            if (tokenId != null && values.get(0) != null) {
                return true;
            }
            String revokedAfter = values.get(1);
            return revokedAfter != null && jwtVerifier.extractIssuedAtMillis(claims) <= Long.parseLong(revokedAfter);
        } catch (Exception e) {
            log.warn("Token revocation check unavailable, accepting the token", e);
            return false;
        }
    }
}
