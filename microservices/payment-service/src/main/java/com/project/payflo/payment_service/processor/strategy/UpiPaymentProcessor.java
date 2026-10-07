package com.project.payflo.payment_service.processor.strategy;

import com.project.payflo.common_lib.dto.PaymentProcessorRequest;
import com.project.payflo.common_lib.dto.PaymentProcessorResponse;
import com.project.payflo.common_lib.util.RandomizerUtil;
import com.project.payflo.payment_service.processor.PaymentProcessor;
import com.project.payflo.payment_service.simulator.CaptureSimulator;
import org.springframework.stereotype.Component;

@Component
public class UpiPaymentProcessor implements PaymentProcessor {

    @Override
    public PaymentProcessorResponse charge(PaymentProcessorRequest request) {
        final String VPA_CODE_FAIL = "fail@okaxis";
        // Authorizes, then the acquirer refuses the capture (see CaptureSimulator).
        final String VPA_CAPTURE_FAIL = "capturefail@okaxis";

        String bankCode = request.methodDetails() != null ?
                request.methodDetails().get("vpa").toString() : null;

        // simulation
        if (VPA_CODE_FAIL.equals(bankCode)) {
            return new PaymentProcessorResponse.Failure("UPI_REJECTED",
                    "Banked rejected the transaction registration"
            );
        }

        String prefix = VPA_CAPTURE_FAIL.equals(bankCode) ? "UPI_PROCESSOR_" + CaptureSimulator.FAIL_TAG + "_" : "UPI_PROCESSOR_";
        String processorRef = prefix + RandomizerUtil.randomBase64(16);

        return new PaymentProcessorResponse.Pending(processorRef);
    }
}
