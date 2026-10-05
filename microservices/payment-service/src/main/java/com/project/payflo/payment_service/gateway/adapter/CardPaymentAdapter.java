package com.project.payflo.payment_service.gateway.adapter;

import com.project.payflo.common_lib.dto.PaymentProcessorResponse;
import com.project.payflo.common_lib.dto.VaultChargeRequest;
import com.project.payflo.payment_service.client.VaultServiceClient;
import com.project.payflo.payment_service.gateway.PaymentAdapter;
import com.project.payflo.payment_service.gateway.dto.PaymentRequest;
import com.project.payflo.payment_service.gateway.dto.PaymentResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@RequiredArgsConstructor
@Component
public class CardPaymentAdapter implements PaymentAdapter {

    private final VaultServiceClient vaultServiceClient;

    @Override
    // No @Retry: a charge that timed out may still have gone through, and sending it again could charge the
    // card twice. The circuit breaker stays; an ambiguous outcome is left for the callback or the timeout
    // sweeper to settle (see PaymentServiceImpl).
    @CircuitBreaker(name = "vault-service")
    public PaymentResult initiate(PaymentRequest request) {
        // PaymentServiceImpl has already checked that methodDetails carries a string token.
        String token = (String) request.methodDetails().get("token");

        PaymentProcessorResponse response = vaultServiceClient.charge(
                new VaultChargeRequest(request.paymentId(), request.merchantId(), token, request.amount(), request.methodDetails())
        );

        return switch (response) {
            case PaymentProcessorResponse.Success success -> new PaymentResult.Success(success.bankReference());
            case PaymentProcessorResponse.Failure failure -> new PaymentResult.Failure(failure.errorCode(), failure.errorDescription());
            case PaymentProcessorResponse.Pending pending -> new PaymentResult.Pending(pending.processorReference());
        };
    }

    @Override
    public PaymentResult capture(UUID paymentId) {
        return new PaymentResult.Success("CARD_REF");
    }
}
