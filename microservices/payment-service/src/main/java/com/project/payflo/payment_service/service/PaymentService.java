package com.project.payflo.payment_service.service;

import com.project.payflo.payment_service.dto.request.PaymentInitRequest;
import com.project.payflo.payment_service.dto.response.PaymentResponse;

import java.util.List;
import java.util.UUID;

public interface PaymentService {

    PaymentResponse initiate(UUID merchantId, PaymentInitRequest request, String idempotencyKey);

    PaymentResponse capture(UUID merchantId, UUID paymentId);

    void resolveAuthorization(UUID paymentId, boolean approve, String bankRef, String errorCode, String errorDescription);

    /**
     * Resolves many payments in ONE transaction: one lock query, one commit and batched writes for all of them
     * instead of a round trip and a log flush per payment. All or nothing; the caller falls back to
     * {@link #resolveAuthorization} one payment at a time when a batch fails.
     */
    void resolveAuthorizations(List<AuthorizationResolution> resolutions);
}
