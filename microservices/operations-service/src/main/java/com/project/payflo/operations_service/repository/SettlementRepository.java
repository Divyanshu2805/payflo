package com.project.payflo.operations_service.repository;

import com.project.payflo.common_lib.enums.SettlementStatus;
import com.project.payflo.operations_service.entity.Settlement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {
    List<Settlement> findByStatus(SettlementStatus settlementStatus);

    // Oldest first; updatedAt is when the settlement last changed, so for TRANSFER_PENDING when the bank accepted it
    List<Settlement> findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(SettlementStatus status, LocalDateTime updatedBefore);

    Slice<Settlement> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Slice<Settlement> findByMerchantIdAndStatusOrderByCreatedAtDesc(UUID merchantId, SettlementStatus status, Pageable pageable);

    Optional<Settlement> findByIdAndMerchantId(UUID id, UUID merchantId);

    // What a run created: the admin API reports it.
    long countByCreatedAtGreaterThanEqual(LocalDateTime since);

    long countByMerchantIdAndCreatedAtGreaterThanEqual(UUID merchantId, LocalDateTime since);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Settlement s where s.id = :id")
    Optional<Settlement> findByIdForUpdate(UUID id);

    // Created but never handed to the bank (a crash between the two steps).
    List<Settlement> findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(SettlementStatus status, LocalDateTime before);

    // Paid out but payment-service not yet told.
    List<Settlement> findTop100ByStatusAndPaymentsSettledAtIsNullAndProcessedAtBeforeOrderByProcessedAtAsc(
            SettlementStatus status, LocalDateTime before);
}
