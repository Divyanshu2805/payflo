package com.project.payflo.operations_service.entity;

import com.project.payflo.common_lib.entity.BaseEntity;
import com.project.payflo.common_lib.enums.WebhookEventStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
// The reconciler reads PENDING/FAILED events whose retry time has passed.
@Table(name = "webhook_event", indexes = {
        @Index(name = "idx_webhook_event_status_next_retry", columnList = "status, next_retry_at"),
        // GET /v1/webhook-deliveries lists a merchant's deliveries newest first.
        @Index(name = "idx_webhook_event_merchant_created", columnList = "merchant_id, created_at")
})
@Builder
@Getter
@Setter
@AllArgsConstructor
@RequiredArgsConstructor
public class WebhookEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    // The exact JSON that is sent and was signed. Sending these bytes (not a re-serialization of `payload`)
    // is what lets a receiver verify the signature over the raw body. Null on rows from before this existed.
    @Column(columnDefinition = "text")
    private String requestBody;

    // Stable across retries, targets and republishing; sent as X-PayFlo-Event-Id and as "id" in the body.
    @Column(length = 100)
    private String eventId;

    // When the change this event reports happened, as stamped by the service that wrote it. Delivery latency (the
    // webhook SLA) is measured from here. Null on rows from before this existed.
    private LocalDateTime eventOccurredAt;

    // The merchant's webhook config this was created for. The delivery asks merchant-service for that config's
    // secret and signs the body when it sends (see WebhookDeliverExecutor); null on rows from before that.
    private UUID configId;

    @Column(nullable = false, length = 255)
    private String targetUrl;

    // Only on rows from before deliveries were signed at send time: computed when the event was created.
    private String signature;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WebhookEventStatus status;

    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    private LocalDateTime nextRetryAt;

    private LocalDateTime lastAttemptAt;

    private Integer lastResponseCode;

    @Column(length = 1000)
    private String lastResponseBody;

    private LocalDateTime deliveredAt;

}
