package com.project.payflo.payment.gateway;

import com.project.payflo.payment.gateway.dto.PaymentRequest;
import com.project.payflo.payment.gateway.dto.PaymentResult;

import java.util.UUID;

public interface PaymentAdapter {

    PaymentResult initiate(PaymentRequest request);

    PaymentResult capture(UUID paymentId);
}
