package com.project.payflo.payment.processor.strategy;

import com.project.payflo.common.util.RandomizerUtil;
import com.project.payflo.payment.processor.PaymentProcessor;
import com.project.payflo.payment.processor.dto.PaymentProcessorRequest;
import com.project.payflo.payment.processor.dto.PaymentProcessorResponse;
import org.springframework.stereotype.Component;

@Component
public class NetBankingPaymentProcessor implements PaymentProcessor {

    @Override
    public PaymentProcessorResponse charge(PaymentProcessorRequest request) {
        final String BANK_CODE_FAIL = "BANK_CODE_FAIL";
        final String BANK_CODE_INSUFFICIENT_FUNDS = "BANK_CODE_INSUFFICIENT_FUNDS";
        final String BANK_CODE_TIMEOUT = "BANK_CODE_TIMEOUT";

        Object bank = request.methodDetails() != null ? request.methodDetails().get("bank") : null;
        String bankCode = bank != null ? bank.toString() : null;

        if (bankCode == null || bankCode.isBlank()) {
            return new PaymentProcessorResponse.Failure("INVALID_BANK", "Bank code is required");
        }

        if (BANK_CODE_FAIL.equals(bankCode)) {
            return new PaymentProcessorResponse.Failure("BANK_REJECTED",
                    "Bank rejected the transaction registration"
            );
        }

        if (BANK_CODE_INSUFFICIENT_FUNDS.equals(bankCode)) {
            return new PaymentProcessorResponse.Failure("INSUFFICIENT_FUNDS",
                    "Insufficient balance in bank account"
            );
        }

        if (BANK_CODE_TIMEOUT.equals(bankCode)) {
            return new PaymentProcessorResponse.Failure("BANK_TIMEOUT",
                    "Bank did not respond in time"
            );
        }

        String processorRef = "NBK_PROCESSOR_"+ RandomizerUtil.randomBase64(16);

        return new PaymentProcessorResponse.Pending(processorRef);
    }
}
