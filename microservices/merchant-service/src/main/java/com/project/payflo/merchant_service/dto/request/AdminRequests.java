package com.project.payflo.merchant_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The request bodies of the platform operator's admin endpoints.
public final class AdminRequests {

    private AdminRequests() {
    }

    // Why, in the operator's words: it goes on the merchant record and into the audit log, so it is required.
    public record SuspendRequest(
            @NotBlank(message = "reason is required")
            @Size(max = 255, message = "reason must be at most 255 characters")
            String reason
    ) {}

    public record ReactivateRequest(
            @Size(max = 255, message = "reason must be at most 255 characters")
            String reason
    ) {}
}
