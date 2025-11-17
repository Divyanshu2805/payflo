package com.project.payflo.vault.service;

import com.project.payflo.common.entity.Money;
import com.project.payflo.payment.processor.dto.PaymentProcessorResponse;
import com.project.payflo.vault.dto.request.TokenizeRequest;
import com.project.payflo.vault.dto.response.TokenizeResponse;

import java.util.Map;
import java.util.UUID;

public interface VaultService {

    TokenizeResponse tokenize(TokenizeRequest request, UUID merchantId);

    PaymentProcessorResponse charge(UUID paymentId, String token, Money amount, Map<String, Object> methodDetails);
}
