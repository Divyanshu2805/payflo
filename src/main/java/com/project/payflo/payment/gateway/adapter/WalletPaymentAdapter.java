package com.project.payflo.payment.gateway.adapter;

import com.project.payflo.common.enums.PaymentMethod;
import com.project.payflo.payment.gateway.PaymentAdapter;
import com.project.payflo.payment.gateway.dto.PaymentRequest;
import com.project.payflo.payment.gateway.dto.PaymentResult;
import com.project.payflo.payment.processor.PaymentProcessorRouter;
import com.project.payflo.payment.processor.dto.PaymentProcessorRequest;
import com.project.payflo.payment.processor.dto.PaymentProcessorResponse;
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
        log.info("Initiate Payment with WalletPaymentAdapter, paymentId: {}", request.paymentId());

        try {
            PaymentProcessorRequest paymentProcessorRequest = PaymentProcessorRequest.nonCard(
                    request.paymentId(),
                    PaymentMethod.WALLET,
                    request.amount(),
                    request.methodDetails()
            );

            PaymentProcessorResponse paymentProcessorResponse =
                    paymentProcessorRouter.charge(paymentProcessorRequest);

            return switch (paymentProcessorResponse) {
                case PaymentProcessorResponse.Failure failure ->
                        new PaymentResult.Failure(failure.errorCode(), failure.errorDescription());

                case PaymentProcessorResponse.Pending pending ->
                        new PaymentResult.Pending(pending.processorReference());

                case PaymentProcessorResponse.Success success -> new PaymentResult.Success(success.bankReference());

            };
        } catch(Exception e) {
            log.warn("Wallet payment failed, paymentId: {}", request.paymentId());
            return new PaymentResult.Failure("WALLET_FAILED", e.getMessage());
        }
    }

    @Override
    public PaymentResult capture(UUID paymentId) {
        return new PaymentResult.Success("WALLET_REF");
    }
}
