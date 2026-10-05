package com.project.payflo.common_lib.exception;

import lombok.Getter;

// The caller is known but not allowed to do this (for example, a team member changing the payout account).
@Getter
public class ForbiddenException extends RuntimeException {

    private final String errorCode;

    public ForbiddenException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
