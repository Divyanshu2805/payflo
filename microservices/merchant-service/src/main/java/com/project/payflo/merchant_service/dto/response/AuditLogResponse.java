package com.project.payflo.merchant_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.AuditActorType;
import com.project.payflo.merchant_service.entity.AuditLog;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditLogResponse(UUID id, UUID merchantId, AuditActorType actorType, String actor, AuditAction action,
                               String targetType, String targetId, Map<String, Object> details, String clientIp,
                               LocalDateTime occurredAt) {

    public static AuditLogResponse from(AuditLog entry) {
        return new AuditLogResponse(entry.getId(), entry.getMerchantId(), entry.getActorType(), entry.getActor(),
                entry.getAction(), entry.getTargetType(), entry.getTargetId(), entry.getDetails(), entry.getClientIp(),
                entry.getOccurredAt());
    }
}
