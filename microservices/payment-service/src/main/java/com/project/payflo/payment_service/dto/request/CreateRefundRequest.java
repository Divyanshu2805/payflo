package com.project.payflo.payment_service.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.Map;

public record CreateRefundRequest(

        // In the payment's smallest currency unit (paise). Omitted refunds whatever is left of the payment.
        @Min(value = 1, message = "amountUnits must be at least 1")
        @Max(value = 500_000_000, message = "amountUnits is too large")
        Integer amountUnits,

        // Anything the merchant wants kept with the refund (a reason, a ticket number).
        Map<String, Object> notes
) {
}
