package com.project.payflo.common_lib.exception;

import lombok.Getter;

// A refresh token that is unknown, expired or already used.
@Getter
public class InvalidTokenException extends RuntimeException {

    private final String errorCode = "INVALID_REFRESH_TOKEN";

    public InvalidTokenException() {
        super("The refresh token is invalid or has expired");
    }
}
