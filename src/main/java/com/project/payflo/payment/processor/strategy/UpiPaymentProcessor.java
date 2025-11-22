package com.project.payflo.payment.processor.strategy;

import com.project.payflo.common.util.RandomizerUtil;
import com.project.payflo.payment.processor.PaymentProcessor;
import com.project.payflo.payment.processor.dto.PaymentProcessorRequest;
import com.project.payflo.payment.processor.dto.PaymentProcessorResponse;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class UpiPaymentProcessor implements PaymentProcessor {

    private static final Pattern VPA_FORMAT = Pattern.compile("^[\\w.\\-]+@[\\w.\\-]+$");

    @Override
    public PaymentProcessorResponse charge(PaymentProcessorRequest request) {
        final String VPA_CODE_FAIL = "fail@okaxis";
        final String VPA_CODE_INSUFFICIENT_FUNDS = "nofunds@okaxis";

        Object vpa = request.methodDetails() != null ? request.methodDetails().get("vpa") : null;
        String vpaCode = vpa != null ? vpa.toString() : null;

        if (vpaCode == null || !VPA_FORMAT.matcher(vpaCode).matches()) {
            return new PaymentProcessorResponse.Failure("INVALID_VPA", "VPA format is invalid");
        }

        if (VPA_CODE_FAIL.equals(vpaCode)) {
            return new PaymentProcessorResponse.Failure("UPI_REJECTED",
                    "Bank rejected the transaction registration"
            );
        }

        if (VPA_CODE_INSUFFICIENT_FUNDS.equals(vpaCode)) {
            return new PaymentProcessorResponse.Failure("INSUFFICIENT_FUNDS",
                    "Insufficient balance linked to VPA"
            );
        }

        String processorRef = "UPI_PROCESSOR_"+ RandomizerUtil.randomBase64(16);

        return new PaymentProcessorResponse.Pending(processorRef);
    }
}
