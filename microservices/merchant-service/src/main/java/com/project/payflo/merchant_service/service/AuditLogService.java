package com.project.payflo.merchant_service.service;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.dto.AuditEntryRequest;
import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.AuditActorType;
import com.project.payflo.merchant_service.dto.response.AuditLogResponse;
import com.project.payflo.merchant_service.entity.AuditLog;
import com.project.payflo.merchant_service.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The audit log of sensitive actions: who did what to which merchant's account, and when.
 *
 * <p>{@link #record} runs in the caller's transaction, so an entry exists if and only if the change it describes
 * was committed — a change that rolls back leaves no entry, and a committed one can't miss it. The actor is read from
 * the request (a dashboard user, an API key, or the platform operator), never taken from the caller's say-so.
 *
 * <p>An entry is never edited or removed (the database refuses it). Its details must not hold a secret: a value whose
 * name looks like one is replaced before it is stored, as a net under the callers' care.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    static final String PLATFORM_ADMIN_LABEL = "platform-admin";
    static final String REDACTED = "[redacted]";

    private final AuditLogRepository auditLogRepository;
    private final MerchantContext merchantContext;

    /** Records an action by whoever is making the current request. */
    @Transactional
    public void record(AuditAction action, UUID merchantId, String targetType, String targetId, Map<String, Object> details) {
        Actor actor = currentActor();
        save(new AuditEntryRequest(action, actor.type(), actor.name(), merchantId, targetType, targetId, details,
                currentClientIp()));
    }

    /** Records an entry another service reports (the platform operator's settlement run, from operations-service). */
    @Transactional
    public void record(AuditEntryRequest entry) {
        save(entry);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> list(UUID merchantId, AuditAction action, int page, int size) {
        PageRequest paging = PageRequest.of(PageResponse.clampPage(page), PageResponse.clampSize(size));
        Slice<AuditLog> slice;
        if (merchantId != null && action != null) {
            slice = auditLogRepository.findByMerchantIdAndActionOrderByOccurredAtDesc(merchantId, action, paging);
        } else if (merchantId != null) {
            slice = auditLogRepository.findByMerchantIdOrderByOccurredAtDesc(merchantId, paging);
        } else if (action != null) {
            slice = auditLogRepository.findByActionOrderByOccurredAtDesc(action, paging);
        } else {
            slice = auditLogRepository.findAllByOrderByOccurredAtDesc(paging);
        }
        return PageResponse.of(slice, AuditLogResponse::from);
    }

    private void save(AuditEntryRequest entry) {
        auditLogRepository.save(AuditLog.builder()
                .merchantId(entry.merchantId())
                .actorType(entry.actorType() != null ? entry.actorType() : AuditActorType.SYSTEM)
                .actor(truncate(entry.actor(), 255))
                .action(entry.action())
                .targetType(truncate(entry.targetType(), 40))
                .targetId(truncate(entry.targetId(), 100))
                .details(sanitize(entry.details()))
                .clientIp(truncate(entry.clientIp(), 64))
                .occurredAt(LocalDateTime.now())
                .build());
        log.info("AUDIT {} merchant={} by {}", entry.action(), entry.merchantId(), entry.actorType());
    }

    private record Actor(AuditActorType type, String name) {}

    private Actor currentActor() {
        try {
            if (merchantContext.isPlatformAdmin()) {
                return new Actor(AuditActorType.PLATFORM_ADMIN, PLATFORM_ADMIN_LABEL);
            }
            if (merchantContext.getUserEmail() != null && !merchantContext.getUserEmail().isBlank()) {
                return new Actor(AuditActorType.USER, merchantContext.getUserEmail());
            }
            if (merchantContext.getKeyId() != null && !merchantContext.getKeyId().isBlank()) {
                return new Actor(AuditActorType.API_KEY, merchantContext.getKeyId());
            }
        } catch (RuntimeException noRequest) {
            // Called outside a request (a job): the platform itself.
        }
        return new Actor(AuditActorType.SYSTEM, "system");
    }

    private String currentClientIp() {
        try {
            return merchantContext.getClientIp();
        } catch (RuntimeException noRequest) {
            return null;
        }
    }

    static Map<String, Object> sanitize(Map<String, Object> details) {
        if (details == null || details.isEmpty()) return null;
        Map<String, Object> safe = new LinkedHashMap<>();
        details.forEach((name, value) -> safe.put(name, looksSecret(name) ? REDACTED : value));
        return safe;
    }

    private static boolean looksSecret(String name) {
        String lower = name.toLowerCase();
        return lower.contains("secret") || lower.contains("password") || lower.contains("token")
                || lower.contains("hash") || lower.contains("cvv") || lower.equals("pan");
    }

    private static String truncate(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }
}
