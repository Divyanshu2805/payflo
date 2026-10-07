package com.project.payflo.payment_service.gateway.adapter;

import com.project.payflo.common_lib.dto.PaymentProcessorRequest;
import com.project.payflo.common_lib.dto.PaymentProcessorResponse;
import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.payment_service.gateway.PaymentAdapter;
import com.project.payflo.payment_service.gateway.dto.PaymentRequest;
import com.project.payflo.payment_service.gateway.dto.PaymentResult;
import com.project.payflo.payment_service.processor.PaymentProcessorRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class WalletPaymentAdapter implements PaymentAdapter {

    private final PaymentProcessorRouter paymentProcessorRouter;

    @Override
    public PaymentResult initiate(PaymentRequest request) {
        log.info("Initiate Payment with Wallet, paymentId: {}", request.paymentId());

        try {
            PaymentProcessorResponse response = paymentProcessorRouter.charge(PaymentProcessorRequest.nonCard(
                    request.paymentId(), PaymentMethod.WALLET, request.amount(), request.methodDetails()));

            return switch (response) {
                case PaymentProcessorResponse.Failure failure ->
                        new PaymentResult.Failure(failure.errorCode(), failure.errorDescription());
                case PaymentProcessorResponse.Pending pending ->
                        new PaymentResult.Pending(pending.processorReference());
                case PaymentProcessorResponse.Success success -> new PaymentResult.Success(success.bankReference());
            };
        } catch (Exception e) {
            // The cause is logged, not returned: this text reaches the merchant in errorDescription.
            log.warn("Wallet failed, paymentId: {}", request.paymentId(), e);
            return new PaymentResult.Failure("WALLET_FAILED", "The payment could not be started");
        }
    }

    @Override
    public PaymentResult capture(UUID paymentId) {
        return new PaymentResult.Success("WALLET_REF");
    }
}
