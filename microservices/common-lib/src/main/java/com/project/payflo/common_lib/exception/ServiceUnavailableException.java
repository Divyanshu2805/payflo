package com.project.payflo.common_lib.exception;

import lombok.Getter;

/** Something this request needs is unavailable right now, and nothing was done: the caller can try again. A 503. */
@Getter
public class ServiceUnavailableException extends RuntimeException {

    private final String errorCode;

    public ServiceUnavailableException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
