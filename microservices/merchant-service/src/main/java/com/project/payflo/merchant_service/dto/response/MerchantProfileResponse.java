package com.project.payflo.merchant_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.project.payflo.common_lib.enums.BusinessType;
import com.project.payflo.common_lib.enums.MerchantStatus;

import java.util.UUID;

// The merchant as its own dashboard sees it. The PAN and the payout account number are masked: they are
// accepted in full when set, never returned in full.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MerchantProfileResponse(
        UUID id,
        String name,
        String email,
        String businessName,
        BusinessType businessType,
        String contactNumber,
        String websiteUrl,
        String gstId,
        String panId,
        MerchantStatus status,
        SettlementBank settlementBank
) {
    public record SettlementBank(String accountNumber, String ifsc, String accountHolderName) {}
}
