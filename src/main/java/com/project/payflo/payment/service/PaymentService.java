package com.project.payflo.payment.service;

import com.project.payflo.payment.dto.request.PaymentInitRequest;
import com.project.payflo.payment.dto.response.PaymentResponse;

import java.util.UUID;

public interface PaymentService {

    PaymentResponse initiate(UUID merchantId, PaymentInitRequest request);

    PaymentResponse capture(UUID merchantId, UUID paymentId);
}
