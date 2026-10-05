package com.project.payflo.common_lib.dto;


import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.common_lib.logging.CardMasker;
import com.project.payflo.common_lib.logging.MaskedCard;

import java.util.Map;
import java.util.UUID;

public record PaymentProcessorRequest(
        UUID processingId,
        UUID paymentId,
        PaymentMethod method,
        Money amount,
        @MaskedCard String pan,
        String expiry,
        Map<String, Object> methodDetails
) {

    public static PaymentProcessorRequest card(UUID paymentId, String pan, String expiry, Money amount, Map<String, Object> details) {
        return new PaymentProcessorRequest(UUID.randomUUID(), paymentId, PaymentMethod.CARD, amount,
                pan, expiry, details);
    }

    public static PaymentProcessorRequest nonCard(UUID paymentId, PaymentMethod method, Money amount, Map<String, Object> details) {
        return new PaymentProcessorRequest(UUID.randomUUID(), paymentId, method, amount,
                null, null, details);
    }

    // A record prints every component by default: this one never prints the card number.
    @Override
    public String toString() {
        return CardMasker.describe(this);
    }
}
