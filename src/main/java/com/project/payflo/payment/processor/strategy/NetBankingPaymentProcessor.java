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

        Object bank = request.methodDetails() != null ? request.methodDetails().get("bank") : null;
        String bankCode = bank != null ? bank.toString() : null;

        // simulation
        if (BANK_CODE_FAIL.equals(bankCode)) {
            return new PaymentProcessorResponse.Failure("BANK_REJECTED",
                    "Banked rejected the transaction registration"
            );
        }

        String processorRef = "NBK_PROCESSOR_"+ RandomizerUtil.randomBase64(16);

        String redirectRef = "http://REDIRECT_BANK.com/"+processorRef;

        return new PaymentProcessorResponse.Success(processorRef, redirectRef);
    }
}
