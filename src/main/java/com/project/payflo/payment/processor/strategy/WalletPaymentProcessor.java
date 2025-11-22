package com.project.payflo.payment.processor.strategy;

import com.project.payflo.common.util.RandomizerUtil;
import com.project.payflo.payment.processor.PaymentProcessor;
import com.project.payflo.payment.processor.dto.PaymentProcessorRequest;
import com.project.payflo.payment.processor.dto.PaymentProcessorResponse;
import org.springframework.stereotype.Component;

@Component
public class WalletPaymentProcessor implements PaymentProcessor {

    @Override
    public PaymentProcessorResponse charge(PaymentProcessorRequest request) {
        final String WALLET_ID_FAIL = "fail_wallet";
        final String WALLET_ID_INSUFFICIENT_BALANCE = "low_balance_wallet";

        Object walletId = request.methodDetails() != null ? request.methodDetails().get("walletId") : null;
        String walletIdStr = walletId != null ? walletId.toString() : null;

        if (walletIdStr == null || walletIdStr.isBlank()) {
            return new PaymentProcessorResponse.Failure("INVALID_WALLET", "Wallet ID is required");
        }

        if (WALLET_ID_FAIL.equals(walletIdStr)) {
            return new PaymentProcessorResponse.Failure("WALLET_REJECTED",
                    "Wallet provider rejected the transaction"
            );
        }

        if (WALLET_ID_INSUFFICIENT_BALANCE.equals(walletIdStr)) {
            return new PaymentProcessorResponse.Failure("INSUFFICIENT_FUNDS",
                    "Insufficient wallet balance"
            );
        }

        String processorRef = "WALLET_PROCESSOR_"+ RandomizerUtil.randomBase64(16);

        return new PaymentProcessorResponse.Pending(processorRef);
    }
}
