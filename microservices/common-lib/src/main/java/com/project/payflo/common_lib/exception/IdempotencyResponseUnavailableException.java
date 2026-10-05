package com.project.payflo.common_lib.exception;

// The request with this key already succeeded, but its response carried a secret that is shown only once and is
// deliberately never stored, so it can't be replayed.
public class IdempotencyResponseUnavailableException extends RuntimeException {

    public IdempotencyResponseUnavailableException(String message) {
        super(message);
    }
}
