package com.project.payflo.payment.gateway.adapter;

import com.project.payflo.payment.gateway.PaymentAdapter;
import com.project.payflo.payment.gateway.dto.PaymentRequest;
import com.project.payflo.payment.gateway.dto.PaymentResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class CardPaymentAdapter implements PaymentAdapter {

    @Override
    public PaymentResult initiate(PaymentRequest request) {
        // TODO: integrate with a real card processor
        return null;
    }
}
