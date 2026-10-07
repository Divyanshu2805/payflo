package com.project.payflo.operations_service.settlement;

import com.project.payflo.common_lib.dto.AuditEntryRequest;
import com.project.payflo.operations_service.client.AuditServiceClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The call that records an admin action in merchant-service's audit log, behind its circuit breaker and retry. */
@Component
@RequiredArgsConstructor
public class AdminAuditGateway {

    private final AuditServiceClient auditServiceClient;

    @CircuitBreaker(name = "merchant-service")
    @Retry(name = "merchant-service")
    public void record(AuditEntryRequest entry) {
        auditServiceClient.record(entry);
    }
}
