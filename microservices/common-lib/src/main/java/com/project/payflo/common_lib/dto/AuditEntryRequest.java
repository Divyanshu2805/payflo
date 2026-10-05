package com.project.payflo.common_lib.dto;

import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.AuditActorType;

import java.util.Map;
import java.util.UUID;

/**
 * An audit entry another service asks merchant-service (which owns the audit log) to record: the platform operator
 * triggering a settlement run from operations-service, say. {@code merchantId} is null for an action on no one merchant.
 * The details must never hold a secret.
 */
public record AuditEntryRequest(AuditAction action, AuditActorType actorType, String actor, UUID merchantId,
                                String targetType, String targetId, Map<String, Object> details, String clientIp) {
}
