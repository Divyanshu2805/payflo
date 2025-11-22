package com.project.payflo.payment.processor.strategy;

import com.project.payflo.common.util.RandomizerUtil;
import com.project.payflo.payment.processor.PaymentProcessor;
import com.project.payflo.payment.processor.dto.PaymentProcessorRequest;
import com.project.payflo.payment.processor.dto.PaymentProcessorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CardPaymentProcessor implements PaymentProcessor {

    public static final String PAN_CARD_DECLINED = "4000000000000002";
    public static final String PAN_CARD_EXPIRED = "4000000000000069";
    public static final String PAN_INSUFFICIENT_FUNDS = "4000000000009995";
    public static final String PAN_PROCESSING_ERROR = "4000000000000119";
    public static final String PAN_FRAUD_SUSPECTED = "4100000000000019";

    @Override
    public PaymentProcessorResponse charge(PaymentProcessorRequest request) {

        String pan = request.pan();

        if (PAN_CARD_DECLINED.equals(pan)) {
            log.warn("Card declined");
            return new PaymentProcessorResponse.Failure("CARD_DECLINED", "Card declined by bank");
        }

        if (PAN_CARD_EXPIRED.equals(pan)) {
            log.warn("Pan card has expired");
            return new PaymentProcessorResponse.Failure("CARD_EXPIRED", "Card has expired");
        }

        if (PAN_INSUFFICIENT_FUNDS.equals(pan)) {
            log.warn("Card declined for insufficient funds");
            return new PaymentProcessorResponse.Failure("INSUFFICIENT_FUNDS", "Insufficient funds on card");
        }

        if (PAN_PROCESSING_ERROR.equals(pan)) {
            log.warn("Card processor error simulated");
            return new PaymentProcessorResponse.Failure("PROCESSING_ERROR",
                    "An error occurred while processing the card");
        }

        if (PAN_FRAUD_SUSPECTED.equals(pan)) {
            log.warn("Card declined by risk/fraud check");
            return new PaymentProcessorResponse.Failure("FRAUD_SUSPECTED",
                    "Transaction blocked by risk check");
        }

        String processorRef = "CARD_PROCESSOR_"+ RandomizerUtil.randomBase64(16);

        return new PaymentProcessorResponse.Pending(processorRef);

    }
}
