package com.project.payflo.common_lib.dto;

import com.project.payflo.common_lib.entity.Money;

import java.util.Map;
import java.util.UUID;

// merchantId is the merchant paying; vault-service only charges a token that merchant created.
public record VaultChargeRequest(
        UUID paymentId,
        UUID merchantId,
        String token,
        Money amount,
        Map<String, Object> methodDetails
) {
}
