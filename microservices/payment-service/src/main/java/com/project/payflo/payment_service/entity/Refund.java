package com.project.payflo.payment_service.entity;

import com.project.payflo.common_lib.entity.BaseEntity;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.RefundStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "refund", indexes = {
        @Index(name = "idx_refund_payment_id", columnList = "payment_id"),
        // The resolver reads PENDING refunds oldest first; the list endpoint reads a merchant's newest first.
        @Index(name = "idx_refund_status_created_at", columnList = "status, created_at"),
        @Index(name = "idx_refund_merchant_created", columnList = "merchant_id, created_at"),
        // The analytics refund queries: a merchant's refunds processed in a date range.
        @Index(name = "idx_refund_merchant_processed", columnList = "merchant_id, processed_at"),
        @Index(name = "idx_refund_merchant_idempotency", columnList = "merchant_id, idempotency_key", unique = true)
})
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Refund extends BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(nullable = false)
    private UUID merchantId;

    // From X-Idempotency-Key: a retry of the same request returns this refund instead of making another.
    @Column(length = 100)
    private String idempotencyKey;

    @Embedded
    private Money amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RefundStatus status = RefundStatus.PENDING;

    @Column(length = 100)
    private String bankReference;

    @Column(length = 100)
    private String errorCode;

    @Column(length = 500)
    private String errorDescription;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> notes;

    private LocalDateTime processedAt;
}
