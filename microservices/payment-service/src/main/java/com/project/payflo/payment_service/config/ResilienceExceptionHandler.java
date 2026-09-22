package com.project.payflo.payment_service.config;

import com.project.payflo.common_lib.exception.ErrorResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// A dependency (merchant-service, vault-service) is failing and its circuit breaker is open: that's
// a temporary 503 the client can retry, not an unexpected 500. Ordered ahead of common-lib's
// GlobalExceptionHandler, whose catch-all Exception handler would otherwise win.
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ResilienceExceptionHandler {

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ErrorResponse> handleDependencyUnavailable(CallNotPermittedException ex) {
        log.warn("Rejected by resilience guard: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "10")
                .body(ErrorResponse.of("DEPENDENCY_UNAVAILABLE", "A downstream service is temporarily unavailable, retry shortly"));
    }
}
