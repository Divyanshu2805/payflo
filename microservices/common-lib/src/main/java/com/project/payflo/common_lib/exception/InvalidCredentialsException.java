package com.project.payflo.common_lib.exception;

import lombok.Getter;

// Login failure. Deliberately the same for "no such user" and "wrong password", so the response
// never reveals whether an email is registered.
@Getter
public class InvalidCredentialsException extends RuntimeException {

    private final String errorCode = "INVALID_CREDENTIALS";

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
