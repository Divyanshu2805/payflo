package com.project.payflo.merchant_service.dto.response;


import com.project.payflo.common_lib.enums.BusinessType;
import com.project.payflo.common_lib.enums.MerchantStatus;

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
