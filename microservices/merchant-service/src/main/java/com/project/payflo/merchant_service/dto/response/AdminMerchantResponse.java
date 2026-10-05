package com.project.payflo.merchant_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.project.payflo.common_lib.enums.MerchantStatus;
import com.project.payflo.merchant_service.entity.Merchant;

import java.time.LocalDateTime;
import java.util.UUID;

/** A merchant as the platform operator sees it: no payout account, PAN or GSTIN. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminMerchantResponse(UUID id, String name, String email, String businessName, MerchantStatus status,
                                    LocalDateTime suspendedAt, String suspensionReason, LocalDateTime createdAt) {

    public static AdminMerchantResponse from(Merchant m) {
        return new AdminMerchantResponse(m.getId(), m.getName(), m.getEmail(), m.getBusinessName(), m.getStatus(),
                m.getSuspendedAt(), m.getSuspensionReason(), m.getCreatedAt());
    }
}
