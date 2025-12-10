package com.project.payflo.common.exception;

import lombok.Getter;

@Getter
public class InvalidRefreshTokenException extends RuntimeException {

    private final String errorCode;

    public InvalidRefreshTokenException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
