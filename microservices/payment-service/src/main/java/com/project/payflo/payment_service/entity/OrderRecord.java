package com.project.payflo.payment_service.entity;

import com.project.payflo.common_lib.entity.BaseEntity;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.OrderStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
// Flyway owns the schema; these describe the indexes it builds. Also there, and not expressible here: the partial index
// idx_order_unpaid_expires_at (expires_at) WHERE order_status IN ('CREATED', 'ATTEMPTED'), which the expiry sweeper
// reads (V4__index_review.sql).
@Table(name = "order_record", indexes = {
        // GET /v1/orders lists a merchant's orders newest first.
        @Index(name = "idx_order_merchant_created", columnList = "merchant_id, created_at"),
        @Index(name = "idx_order_merchant_receipt", columnList = "merchant_id, receipt", unique = true),
        // From X-Idempotency-Key: a retry of the same request returns this order instead of making another.
        @Index(name = "idx_order_merchant_idempotency", columnList = "merchant_id, idempotency_key", unique = true)
})
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class OrderRecord  extends BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    // no FK — cross-service boundary
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    // The X-Idempotency-Key the order was created with, if any.
    @Column(length = 100)
    private String idempotencyKey;

    @Column(name = "customer_id")
    private UUID customerId;

    @Embedded
    private Money amount;

    @Column(length = 100)
    private String receipt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OrderStatus orderStatus = OrderStatus.CREATED;

    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @JdbcTypeCode((SqlTypes.JSON))
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> notes;

    @Column(nullable = false)
    private LocalDateTime expiresAt;
}
