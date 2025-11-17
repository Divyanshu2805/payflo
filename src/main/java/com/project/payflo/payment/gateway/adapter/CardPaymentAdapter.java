package com.project.payflo.payment.gateway.adapter;

import com.project.payflo.payment.gateway.PaymentAdapter;
import com.project.payflo.payment.gateway.dto.PaymentRequest;
import com.project.payflo.payment.gateway.dto.PaymentResult;
import com.project.payflo.payment.processor.dto.PaymentProcessorResponse;
import com.project.payflo.vault.service.VaultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@RequiredArgsConstructor
@Component
@Slf4j
public class CardPaymentAdapter implements PaymentAdapter {

    private final VaultService vaultService;

    @Override
    public PaymentResult initiate(PaymentRequest request) {
        try {
            String token = request.methodDetails() != null
                    ? (String) request.methodDetails().get("token") : null;

            PaymentProcessorResponse response = vaultService.charge(
                    request.paymentId(), token, request.amount(), request.methodDetails()
            );

            return switch (response) {
                case PaymentProcessorResponse.Success success -> new PaymentResult.Success(success.bankReference());
                case PaymentProcessorResponse.Failure failure -> new PaymentResult.Failure(failure.errorCode(), failure.errorDescription());
                case PaymentProcessorResponse.Pending pending -> new PaymentResult.Pending(pending.processorReference());
            };
        } catch (Exception e) {
            log.warn("Card payment failed, paymentId: {}", request.paymentId());
            return new PaymentResult.Failure("CARD_FAILED", e.getMessage());
        }
    }

    @Override
    public PaymentResult capture(UUID paymentId) {
        return new PaymentResult.Success("CARD_REF");
    }
}
