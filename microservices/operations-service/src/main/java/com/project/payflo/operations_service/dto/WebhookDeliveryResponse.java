package com.project.payflo.operations_service.dto;

import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.operations_service.entity.WebhookEvent;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

// One delivery of one event to one endpoint, as the merchant sees it. The signature is not included.
public record WebhookDeliveryResponse(
        UUID id,
        String eventId,
        String eventType,
        String targetUrl,
        WebhookEventStatus status,
        Integer attempts,
        LocalDateTime nextRetryAt,
        LocalDateTime lastAttemptAt,
        Integer lastResponseCode,
        String lastResponseBody,
        LocalDateTime deliveredAt,
        Map<String, Object> payload,
        LocalDateTime createdAt
) {
    public static WebhookDeliveryResponse from(WebhookEvent e) {
        return new WebhookDeliveryResponse(e.getId(), e.getEventId(), e.getEventType(), e.getTargetUrl(), e.getStatus(),
                e.getAttempts(), e.getNextRetryAt(), e.getLastAttemptAt(), e.getLastResponseCode(),
                e.getLastResponseBody(), e.getDeliveredAt(), e.getPayload(), e.getCreatedAt());
    }
}
