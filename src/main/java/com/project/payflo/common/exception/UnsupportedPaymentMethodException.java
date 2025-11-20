package com.project.payflo.common.exception;

import lombok.Getter;

@Getter
public class UnsupportedPaymentMethodException extends RuntimeException {

    private final String errorCode;

    public UnsupportedPaymentMethodException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
