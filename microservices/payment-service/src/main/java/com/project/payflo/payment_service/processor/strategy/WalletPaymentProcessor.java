package com.project.payflo.payment_service.processor.strategy;

import com.project.payflo.common_lib.dto.PaymentProcessorRequest;
import com.project.payflo.common_lib.dto.PaymentProcessorResponse;
import com.project.payflo.common_lib.util.RandomizerUtil;
import com.project.payflo.payment_service.processor.PaymentProcessor;
import com.project.payflo.payment_service.simulator.CaptureSimulator;
import org.springframework.stereotype.Component;

// Simulated wallet acquirer, like the UPI and net-banking ones: a trigger value declines, anything else
// is registered and left for the bank callback simulator to resolve.
@Component
public class WalletPaymentProcessor implements PaymentProcessor {

    static final String WALLET_FAIL = "wallet_fail";
    // Authorizes, then the acquirer refuses the capture (see CaptureSimulator).
    static final String WALLET_CAPTURE_FAIL = "wallet_capture_fail";

    @Override
    public PaymentProcessorResponse charge(PaymentProcessorRequest request) {
        Object wallet = request.methodDetails() != null ? request.methodDetails().get("wallet") : null;

        if (wallet != null && WALLET_FAIL.equals(wallet.toString())) {
            return new PaymentProcessorResponse.Failure("WALLET_REJECTED",
                    "The wallet provider rejected the transaction registration");
        }

        String prefix = wallet != null && WALLET_CAPTURE_FAIL.equals(wallet.toString())
                ? "WALLET_PROCESSOR_" + CaptureSimulator.FAIL_TAG + "_" : "WALLET_PROCESSOR_";
        return new PaymentProcessorResponse.Pending(prefix + RandomizerUtil.randomBase64(16));
    }
}
