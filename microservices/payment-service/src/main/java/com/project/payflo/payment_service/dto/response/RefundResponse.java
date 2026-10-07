package com.project.payflo.payment_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.RefundStatus;
import com.project.payflo.payment_service.entity.Refund;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundResponse(
        UUID id,
        UUID paymentId,
        UUID merchantId,
        Money amount,
        RefundStatus status,
        Map<String, Object> notes,
        String errorCode,
        String errorDescription,
        LocalDateTime processedAt,
        LocalDateTime createdAt
) {
    public static RefundResponse from(Refund r) {
        return new RefundResponse(r.getId(), r.getPayment().getId(), r.getMerchantId(), r.getAmount(), r.getStatus(),
                r.getNotes(), r.getErrorCode(), r.getErrorDescription(), r.getProcessedAt(), r.getCreatedAt());
    }
}
