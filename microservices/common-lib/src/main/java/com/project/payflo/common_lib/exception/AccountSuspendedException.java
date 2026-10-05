package com.project.payflo.common_lib.exception;

import lombok.Getter;

// The merchant account exists and the credentials were right, but the account is suspended.
@Getter
public class AccountSuspendedException extends RuntimeException {

    private final String errorCode = "MERCHANT_SUSPENDED";

    public AccountSuspendedException() {
        super("This merchant account is suspended");
    }
}
