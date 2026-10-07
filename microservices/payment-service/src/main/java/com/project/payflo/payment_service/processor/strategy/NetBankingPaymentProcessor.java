package com.project.payflo.payment_service.processor.strategy;

import com.project.payflo.common_lib.dto.PaymentProcessorRequest;
import com.project.payflo.common_lib.dto.PaymentProcessorResponse;
import com.project.payflo.common_lib.util.RandomizerUtil;
import com.project.payflo.payment_service.processor.PaymentProcessor;
import com.project.payflo.payment_service.simulator.CaptureSimulator;
import org.springframework.stereotype.Component;

@Component
public class NetBankingPaymentProcessor implements PaymentProcessor {

    @Override
    public PaymentProcessorResponse charge(PaymentProcessorRequest request) {

        final String BANK_CODE_FAIL = "BANK_CODE_FAIL";
        // Authorizes, then the acquirer refuses the capture (see CaptureSimulator).
        final String BANK_CODE_CAPTURE_FAIL = "BANK_CODE_CAPTURE_FAIL";

        String bankCode = request.methodDetails() != null ?
                request.methodDetails().get("bank").toString() : null;

        // simulation
        if (BANK_CODE_FAIL.equals(bankCode)) {
            return new PaymentProcessorResponse.Failure("BANK_REJECTED",
                    "Banked rejected the transaction registration"
                    );
        }

        String prefix = BANK_CODE_CAPTURE_FAIL.equals(bankCode) ? "NBK_PROCESSOR_" + CaptureSimulator.FAIL_TAG + "_" : "NBK_PROCESSOR_";
        String processorRef = prefix + RandomizerUtil.randomBase64(16);

//        String redirectRef = "http://REDIRECT_BANK.com/"+processorRef;

        return new PaymentProcessorResponse.Pending(processorRef);
    }
}
