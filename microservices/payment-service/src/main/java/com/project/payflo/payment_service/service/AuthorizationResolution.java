package com.project.payflo.payment_service.service;

import java.util.UUID;

/** The bank's answer to one payment's authorization: approved (with its reference) or declined (with why). */
public record AuthorizationResolution(UUID paymentId, boolean approve, String bankRef,
                                      String errorCode, String errorDescription) {

    public static AuthorizationResolution approved(UUID paymentId, String bankRef) {
        return new AuthorizationResolution(paymentId, true, bankRef, null, null);
    }

    public static AuthorizationResolution declined(UUID paymentId, String errorCode, String errorDescription) {
        return new AuthorizationResolution(paymentId, false, null, errorCode, errorDescription);
    }
}
