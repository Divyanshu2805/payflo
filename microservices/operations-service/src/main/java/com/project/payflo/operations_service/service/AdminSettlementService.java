package com.project.payflo.operations_service.service;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.dto.AuditEntryRequest;
import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.AuditActorType;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.DuplicateResourceException;
import com.project.payflo.common_lib.exception.ServiceUnavailableException;
import com.project.payflo.operations_service.client.MerchantServiceClient;
import com.project.payflo.operations_service.dto.AdminSettlementRunResponse;
import com.project.payflo.operations_service.repository.SettlementRepository;
import com.project.payflo.operations_service.settlement.AdminAuditGateway;
import com.project.payflo.operations_service.settlement.SettlementEngine;
import com.project.payflo.operations_service.settlement.SettlementTransactionExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A settlement run on demand, for the platform operator: every active merchant, or just one. It is the nightly run
 * (same code, same lock) started by hand, so a demo doesn't need the cron edited and an operator can settle a merchant
 * that failed.
 *
 * <p>Deliberately not transactional: a run calls three other services, and the steps that write are their own short
 * transactions (see {@code SettlementTransactionExecutor}). The run is audited first, so there is no unaudited admin
 * action: if the audit log can't be written nothing runs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSettlementService {

    // The nightly job's lock (SettlementEngine.runScheduled): a manual run and the nightly one never overlap.
    static final String LOCK_NAME = "operations-service-settlement-engine";

    private final SettlementEngine settlementEngine;
    private final SettlementTransactionExecutor executor;
    private final MerchantServiceClient merchantServiceClient;
    private final SettlementRepository settlementRepository;
    private final AdminAuditGateway auditGateway;
    private final LockProvider lockProvider;
    private final MerchantContext merchantContext;

    public AdminSettlementRunResponse run(UUID merchantId) {
        if (merchantId != null && !merchantServiceClient.listActiveMerchantIds().contains(merchantId)) {
            throw new BusinessRuleViolationException("MERCHANT_NOT_ACTIVE",
                    "Only an ACTIVE merchant can be settled: it is unknown, still waiting for KYC, or suspended");
        }

        audit(merchantId);

        Optional<SimpleLock> lock = lockProvider.lock(new LockConfiguration(Instant.now(), LOCK_NAME,
                Duration.ofHours(2), Duration.ZERO));
        if (lock.isEmpty()) {
            throw new DuplicateResourceException("SETTLEMENT_RUN_IN_PROGRESS",
                    "A settlement run is already in progress; try again when it has finished");
        }
        try {
            LocalDateTime startedAt = LocalDateTime.now();
            int merchants;
            int failed;
            if (merchantId == null) {
                SettlementEngine.RunSummary summary = settlementEngine.run();
                merchants = summary.merchants();
                failed = summary.failedMerchants();
            } else {
                merchants = 1;
                failed = 0;
                try {
                    executor.processForMerchant(merchantId, LocalDate.now());
                } catch (Exception e) {
                    failed = 1;
                    log.error("Settlement failed for merchantId: {}", merchantId, e);
                }
            }
            long created = merchantId == null
                    ? settlementRepository.countByCreatedAtGreaterThanEqual(startedAt)
                    : settlementRepository.countByMerchantIdAndCreatedAtGreaterThanEqual(merchantId, startedAt);
            return new AdminSettlementRunResponse(startedAt, LocalDateTime.now(), merchants, failed, created);
        } finally {
            lock.get().unlock();
        }
    }

    private void audit(UUID merchantId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scope", merchantId == null ? "ALL_ACTIVE_MERCHANTS" : "ONE_MERCHANT");
        try {
            auditGateway.record(new AuditEntryRequest(AuditAction.SETTLEMENT_RUN_TRIGGERED, AuditActorType.PLATFORM_ADMIN,
                    "platform-admin", merchantId, merchantId == null ? null : "MERCHANT",
                    merchantId == null ? null : merchantId.toString(), details, clientIp()));
        } catch (Exception e) {
            log.error("Could not record the settlement run in the audit log; not running it", e);
            throw new ServiceUnavailableException("AUDIT_LOG_UNAVAILABLE",
                    "The audit log could not be written, so nothing was run. Try again shortly");
        }
    }

    private String clientIp() {
        try {
            return merchantContext.getClientIp();
        } catch (RuntimeException noRequest) {
            return null;
        }
    }
}
