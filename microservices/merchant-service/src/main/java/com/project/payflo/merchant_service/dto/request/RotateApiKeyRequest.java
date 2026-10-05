package com.project.payflo.merchant_service.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record RotateApiKeyRequest(

        // How long the old secret keeps working after rotation: 0 (it stops at once, for a leaked
        // secret) to 24 hours. Omitted means 24 hours.
        @Min(value = 0, message = "gracePeriodHours must be between 0 and 24")
        @Max(value = 24, message = "gracePeriodHours must be between 0 and 24")
        Integer gracePeriodHours
) {
}
