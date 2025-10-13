package com.project.payflo.merchant.dto.response;

import com.project.payflo.common.enums.BusinessType;
import com.project.payflo.common.enums.MerchantStatus;
import lombok.Data;

import java.util.UUID;

public record MerchantResponse(
        UUID id,
        String name,
        String email,
        String businessName,
        BusinessType businessType,
        MerchantStatus merchantStatus
) {
}
