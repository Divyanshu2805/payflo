package com.project.payflo.merchant_service.dto.response;

public record LoginResponse(
        String accessToken,
        // Exchange for a new pair at POST /v1/auth/refresh. Null if it couldn't be issued (Redis down).
        String refreshToken,
        long expiresInSeconds
) {
}
