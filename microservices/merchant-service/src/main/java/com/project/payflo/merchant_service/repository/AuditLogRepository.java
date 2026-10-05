package com.project.payflo.merchant_service.repository;

import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.merchant_service.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    // Newest first. The four combinations of the two optional filters, each on an index.

    Slice<AuditLog> findAllByOrderByOccurredAtDesc(Pageable pageable);

    Slice<AuditLog> findByMerchantIdOrderByOccurredAtDesc(UUID merchantId, Pageable pageable);

    Slice<AuditLog> findByActionOrderByOccurredAtDesc(AuditAction action, Pageable pageable);

    Slice<AuditLog> findByMerchantIdAndActionOrderByOccurredAtDesc(UUID merchantId, AuditAction action, Pageable pageable);
}
