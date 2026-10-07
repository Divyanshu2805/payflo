package com.project.payflo.operations_service.dto;

import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.SettlementStatus;
import com.project.payflo.operations_service.entity.Settlement;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementResponse(
        UUID id,
        SettlementStatus status,
        Money grossAmount,
        Money refundAmount,
        Money feeAmount,
        Money gstAmount,
        Money netAmount,
        String bankReference,
        LocalDateTime processedAt,
        String failureReason,
        LocalDateTime createdAt
) {
    public static SettlementResponse from(Settlement s) {
        return new SettlementResponse(s.getId(), s.getStatus(), s.getGrossAmount(), s.getRefundAmount(),
                s.getFeeAmount(), s.getGstAmount(), s.getNetAmount(), s.getBankReference(), s.getProcessedAt(),
                s.getFailureReason(), s.getCreatedAt());
    }
}
