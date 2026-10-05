package com.project.payflo.common_lib.exception;

import lombok.Getter;

/**
 * A request refused because too much of one kind of activity (tokenizing cards, failed card payments) has happened in
 * a short time: the pattern of someone testing stolen card numbers. A 429 with its own error code and a
 * {@code Retry-After}, so a merchant's client can tell it from the ordinary request rate limit.
 */
@Getter
public class VelocityLimitException extends RuntimeException {

    private final String errorCode;
    private final long retryAfterSeconds;

    public VelocityLimitException(String errorCode, String message, long retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
