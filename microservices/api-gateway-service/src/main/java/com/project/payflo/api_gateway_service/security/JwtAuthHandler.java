package com.project.payflo.api_gateway_service.security;

import com.project.payflo.common_lib.exception.RateLimitException;
import com.project.payflo.common_lib.ratelimit.RateLimitResult;
import com.project.payflo.common_lib.ratelimit.RateLimiter;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class JwtAuthHandler {

    private final JwtVerifier jwtVerifier;
    private final RateLimiter rateLimiter;

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
        if (merchantId == null || role == null) {
            throw new GatewayAuthenticationException("Invalid or expired token");
        }

        RateLimitResult rateLimitResult = rateLimiter.check("jwt:" + merchantId, requestsPerMinute, 60);
        if (!rateLimitResult.isAllowed()) {
            throw new RateLimitException("Too many requests", rateLimitResult.retryAfterSeconds());
        }

        return Map.of(
                "X-Merchant-Id", merchantId,
                "X-User-Role", role
        );
    }
}
