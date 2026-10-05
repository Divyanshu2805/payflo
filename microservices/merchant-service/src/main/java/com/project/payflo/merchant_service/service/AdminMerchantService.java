package com.project.payflo.merchant_service.service;

import com.project.payflo.common_lib.cache.MerchantStatusCacheKey;
import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.MerchantStatus;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.merchant_service.dto.response.AdminMerchantResponse;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the platform operator can do to a merchant, through the admin API. Only the gateway can let a request reach
 * these (it checks the admin key), and each change is recorded in the audit log in the same transaction.
 *
 * <p>A suspension also writes the status the gateway remembers, once the change has committed, so it applies at once
 * rather than when that entry expires a minute later. It is the one thing that stops a merchant's existing API keys and
 * dashboard sessions, since the gateway refuses a suspended merchant whatever credential it presents.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMerchantService {

    private final MerchantRepository merchantRepository;
    private final AuditLogService auditLogService;
    private final StringRedisTemplate redis;

    @Transactional(readOnly = true)
    public PageResponse<AdminMerchantResponse> list(MerchantStatus status, int page, int size) {
        PageRequest paging = PageRequest.of(PageResponse.clampPage(page), PageResponse.clampSize(size));
        Slice<Merchant> slice = status != null
                ? merchantRepository.findByStatusOrderByCreatedAtDesc(status, paging)
                : merchantRepository.findAllByOrderByCreatedAtDesc(paging);
        return PageResponse.of(slice, AdminMerchantResponse::from);
    }

    @Transactional(readOnly = true)
    public AdminMerchantResponse get(UUID merchantId) {
        return AdminMerchantResponse.from(require(merchantId));
    }

    @Transactional
    public AdminMerchantResponse suspend(UUID merchantId, String reason) {
        Merchant merchant = require(merchantId);
        if (merchant.getStatus() == MerchantStatus.SUSPENDED) {
            throw new BusinessRuleViolationException("MERCHANT_ALREADY_SUSPENDED", "This merchant is already suspended");
        }

        MerchantStatus before = merchant.getStatus();
        merchant.setStatusBeforeSuspension(before);
        merchant.setStatus(MerchantStatus.SUSPENDED);
        merchant.setSuspendedAt(LocalDateTime.now());
        merchant.setSuspensionReason(reason);
        merchantRepository.save(merchant);

        auditLogService.record(AuditAction.MERCHANT_SUSPENDED, merchantId, "MERCHANT", merchantId.toString(),
                Map.of("reason", reason, "previousStatus", before.name()));
        publishStatusAfterCommit(merchantId, MerchantStatus.SUSPENDED);
        log.warn("Merchant {} suspended: {}", merchantId, reason);
        return AdminMerchantResponse.from(merchant);
    }

    /**
     * Lifts a suspension and puts the merchant back to the status it had. One suspended before it passed KYC goes back to
     * waiting for KYC rather than to ACTIVE; so does one suspended some other way, whose earlier status is unknown.
     */
    @Transactional
    public AdminMerchantResponse reactivate(UUID merchantId, String reason) {
        Merchant merchant = require(merchantId);
        if (merchant.getStatus() != MerchantStatus.SUSPENDED) {
            throw new BusinessRuleViolationException("MERCHANT_NOT_SUSPENDED", "This merchant is not suspended");
        }

        MerchantStatus restored = merchant.getStatusBeforeSuspension() != null
                ? merchant.getStatusBeforeSuspension() : MerchantStatus.PENDING_KYC;
        merchant.setStatus(restored);
        merchant.setStatusBeforeSuspension(null);
        merchant.setSuspendedAt(null);
        merchant.setSuspensionReason(null);
        merchantRepository.save(merchant);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("restoredStatus", restored.name());
        if (reason != null && !reason.isBlank()) {
            details.put("reason", reason);
        }
        auditLogService.record(AuditAction.MERCHANT_REACTIVATED, merchantId, "MERCHANT", merchantId.toString(), details);
        publishStatusAfterCommit(merchantId, restored);
        log.warn("Merchant {} reactivated as {}", merchantId, restored);
        return AdminMerchantResponse.from(merchant);
    }

    private Merchant require(UUID merchantId) {
        return merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", merchantId));
    }

    // After the commit, so the gateway can't read the new status and then see the old row; and never a failure of the
    // change itself, since the entry expires on its own within a minute.
    private void publishStatusAfterCommit(UUID merchantId, MerchantStatus status) {
        Runnable publish = () -> {
            try {
                redis.opsForValue().set(MerchantStatusCacheKey.of(merchantId), status.name(), MerchantStatusCacheKey.TTL);
            } catch (Exception e) {
                log.warn("Could not update the gateway's status cache for merchant {}; it applies within a minute", merchantId, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
