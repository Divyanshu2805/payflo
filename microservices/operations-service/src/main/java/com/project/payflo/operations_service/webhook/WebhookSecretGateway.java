package com.project.payflo.operations_service.webhook;

import com.project.payflo.common_lib.dto.WebhookTarget;
import com.project.payflo.operations_service.client.MerchantServiceClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** The call to merchant-service for a webhook config, behind its circuit breaker and retry. */
@Component
@RequiredArgsConstructor
public class WebhookSecretGateway {

    private final MerchantServiceClient merchantServiceClient;

    @CircuitBreaker(name = "merchant-service")
    @Retry(name = "merchant-service")
    public WebhookTarget getWebhookTarget(UUID merchantId, UUID configId) {
        return merchantServiceClient.getWebhookTarget(merchantId, configId);
    }
}
