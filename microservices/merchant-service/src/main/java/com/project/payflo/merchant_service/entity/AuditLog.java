package com.project.payflo.merchant_service.entity;

import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.AuditActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One line of the audit log. Append-only: the database refuses an UPDATE or DELETE (see V2), so there is no
 * BaseEntity here, with its modified-at and modified-by, and nothing to change afterwards.
 */
@Entity
@Table(name = "audit_log", indexes = {
        // A merchant's own history, newest first.
        @Index(name = "idx_audit_log_merchant_occurred", columnList = "merchant_id, occurred_at"),
        // The platform-wide history for the admin API.
        @Index(name = "idx_audit_log_occurred", columnList = "occurred_at")
})
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AuditLog {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    // A plain id: null for an action that is on no one merchant.
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuditActorType actorType;

    // An email, an API key's public id, or a fixed label; never a secret.
    @Column(length = 255)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuditAction action;

    @Column(length = 40)
    private String targetType;

    @Column(length = 100)
    private String targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(length = 64)
    private String clientIp;

    @Column(nullable = false)
    private LocalDateTime occurredAt;
}
