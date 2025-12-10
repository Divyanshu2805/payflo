package com.project.payflo.merchant.dto.response;

public record LoginResponse(
        String accessToken,
        String refreshToken
) {
}
