package com.project.payflo.payment_service.repository;

import com.project.payflo.common_lib.enums.RefundStatus;
import com.project.payflo.payment_service.entity.Refund;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByIdAndMerchantId(UUID refundId, UUID merchantId);

    Optional<Refund> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :refundId")
    Optional<Refund> findByIdForUpdate(UUID refundId);

    List<Refund> findByPayment_IdAndMerchantIdOrderByCreatedAtAsc(UUID paymentId, UUID merchantId);

    Slice<Refund> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Slice<Refund> findByMerchantIdAndStatusOrderByCreatedAtDesc(UUID merchantId, RefundStatus status, Pageable pageable);

    // The resolver's work list: the oldest refunds still waiting on the (simulated) bank.
    List<Refund> findTop200ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(RefundStatus status, LocalDateTime before);

    // How much of a payment is already spoken for: refunds that are waiting, in progress or done.
    @Query("select coalesce(sum(r.amount.amountUnits), 0) from Refund r "
            + "where r.payment.id = :paymentId and r.status in :statuses")
    long sumAmountUnits(UUID paymentId, Collection<RefundStatus> statuses);

    // Refunded so far per payment, for settlement: only refunds the bank has completed.
    @Query("select r.payment.id, sum(r.amount.amountUnits) from Refund r "
            + "where r.payment.id in :paymentIds and r.status = com.project.payflo.common_lib.enums.RefundStatus.PROCESSED "
            + "group by r.payment.id")
    List<Object[]> sumProcessedByPayment(Collection<UUID> paymentIds);

    // Analytics: refunds the bank has completed, per day (day, count, sum of amount_units).
    @Query(value = "select cast(processed_at as date), count(*), coalesce(sum(amount_units), 0) from refund "
            + "where merchant_id = :merchantId and status = 'PROCESSED' and processed_at >= :from and processed_at < :to "
            + "group by 1 order by 1", nativeQuery = true)
    List<Object[]> processedByDay(UUID merchantId, LocalDateTime from, LocalDateTime to);
}
