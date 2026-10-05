package com.project.payflo.merchant_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The small bodies of the session endpoints, kept together.
public final class SessionRequests {

    private SessionRequests() {
    }

    public record RefreshRequest(
            @NotBlank(message = "refreshToken is required")
            String refreshToken
    ) {}

    // The refresh token is optional: with it, the refresh token is revoked as well as the access token.
    public record LogoutRequest(
            String refreshToken
    ) {}

    public record ChangePasswordRequest(
            @NotBlank(message = "currentPassword is required")
            String currentPassword,

            @NotBlank(message = "newPassword is required")
            @Size(min = 8, max = 100, message = "newPassword must be 8 to 100 characters long")
            String newPassword
    ) {}
}
