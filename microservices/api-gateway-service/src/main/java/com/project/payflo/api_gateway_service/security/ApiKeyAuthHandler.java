package com.project.payflo.api_gateway_service.security;

import com.project.payflo.api_gateway_service.client.ApiKeyLookupClient;
import com.project.payflo.common_lib.cache.ApiKeyCache;
import com.project.payflo.common_lib.cache.ApiKeyCacheEntry;
import com.project.payflo.common_lib.exception.RateLimitException;
import com.project.payflo.common_lib.ratelimit.RateLimitResult;
import com.project.payflo.common_lib.ratelimit.RateLimiter;
import feign.FeignException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;


@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthHandler {

    private static final String BASIC_PREFIX = "Basic ";
    private static final String SECRET_VERIFY_PREFIX = "apikey:secret-verified:";
    private static final Duration SECRET_VERIFY_TTL = Duration.ofSeconds(30);
    private static final String MISSING_KEY_PREFIX = "apikey:missing:";
    private static final Duration MISSING_KEY_TTL = Duration.ofSeconds(60);
    // pf_<environment>_<random>: what create() issues. Anything else can't be a key id, so it is
    // refused before touching Redis or merchant-service.
    private static final Pattern KEY_ID_FORMAT = Pattern.compile("^pf_[a-z]{1,10}_[A-Za-z0-9_-]{1,64}$");
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    private final ApiKeyCache apiKeyCache;
    private final ApiKeyLookupClient apiKeyLookupClient;
    private final RateLimiter rateLimiter;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${app.rate-limit.use-case.api-key.requests-per-minute:60}")
    private int requestsPerMinute;

    public Map<String, String> authenticate(String authHeader, HttpServletResponse response) {
        String[] credentials = decodeBasic(authHeader);
        if (credentials == null) {
            throw new GatewayAuthenticationException("Malformed API key header");
        }

        String keyId = credentials[0];
        String rawSecret = credentials[1];

        if (!KEY_ID_FORMAT.matcher(keyId).matches()) {
            throw new GatewayAuthenticationException("Invalid or missing API key");
        }

        ApiKeyCacheEntry entry = apiKeyCache.get(keyId).orElseGet(() -> loadAndCache(keyId));

        if (entry == null || !entry.enabled() || !secretMatches(rawSecret, entry)) {
            throw new GatewayAuthenticationException("Invalid or missing API key");
        }

        RateLimitResult rateLimitResult = rateLimiter.check("apikey:" + keyId, requestsPerMinute, 60);
        if (!rateLimitResult.isAllowed()) {
            throw new RateLimitException("Too many requests", rateLimitResult.retryAfterSeconds());
        }

        response.setHeader("X-RateLimit-Limit", String.valueOf(requestsPerMinute));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(rateLimitResult.remaining()));

        return Map.of(
                "X-Merchant-Id", entry.merchantId().toString(),
                "X-Environment", entry.environment().name(),
                "X-Key-Id", entry.keyId()
        );
    }

    private ApiKeyCacheEntry loadAndCache(String keyId) {
        if (knownToBeMissing(keyId)) {
            return null;
        }
        try {
            ApiKeyCacheEntry entry = apiKeyLookupClient.findByKeyId(keyId);
            apiKeyCache.put(keyId, entry);
            return entry;
        } catch (FeignException.NotFound e) {
            // Remember it, so repeated guesses at the same id don't each cost a database lookup.
            rememberMissing(keyId);
            return null;
        } catch (Exception e) {
            log.warn("API key lookup failed keyId={}", keyId, e);
            return null;
        }
    }

    private boolean knownToBeMissing(String keyId) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(MISSING_KEY_PREFIX + keyId));
        } catch (Exception e) {
            return false;
        }
    }

    private void rememberMissing(String keyId) {
        try {
            stringRedisTemplate.opsForValue().set(MISSING_KEY_PREFIX + keyId, "1", MISSING_KEY_TTL);
        } catch (Exception e) {
            log.warn("Could not remember a missing API key id");
        }
    }

    private boolean secretMatches(String rawSecret, ApiKeyCacheEntry entry) {

        String cacheKey = SECRET_VERIFY_PREFIX + entry.keyId() + ":" + entry.keySecretHash() + ":" + sha256(rawSecret);

        try {
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(cacheKey))) {
                return true;
            }
        } catch (Exception e) {
            log.warn("Secret verification cache read failed, keyId: {}", entry.keyId());
        }

        boolean matches = BCRYPT.matches(rawSecret, entry.keySecretHash())
                || (entry.isInGracePeriod()
                        && entry.previousKeySecretHash() != null
                        && BCRYPT.matches(rawSecret, entry.previousKeySecretHash()));

        if (matches) {
            try {
                stringRedisTemplate.opsForValue().set(cacheKey, "true", SECRET_VERIFY_TTL);
            } catch (Exception e) {
                log.warn("Secret verification cache put failed, keyId: {}", entry.keyId());
            }
        }

        return matches;
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String[] decodeBasic(String header) {
        try {
            String encoded = header.substring(BASIC_PREFIX.length());
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 1) return null;
            return new String[]{decoded.substring(0, colon), decoded.substring(colon + 1)};
        } catch (Exception e) {
            return null;
        }
    }
}
