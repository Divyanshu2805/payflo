package com.project.payflo.common_lib.exception;

// The same X-Idempotency-Key arrived with a different request: replaying the first response would answer a
// question the caller didn't ask.
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
