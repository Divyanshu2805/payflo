package com.project.payflo.payment_service.repository;

import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.payment_service.entity.OrderRecord;
import com.project.payflo.payment_service.entity.Payment;
import io.micrometer.observation.ObservationFilter;
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

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    // An order's payments, oldest first. Scoped by merchant, like every other read.
    List<Payment> findByOrder_IdAndMerchantIdOrderByCreatedAtAsc(UUID orderId, UUID merchantId);

    // List endpoint, newest first. A page is a Slice: no COUNT over a merchant's whole history.
    Slice<Payment> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Slice<Payment> findByMerchantIdAndStatusOrderByCreatedAtDesc(UUID merchantId, PaymentStatus status, Pageable pageable);

    Slice<Payment> findByMerchantIdAndOrder_IdOrderByCreatedAtDesc(UUID merchantId, UUID orderId, Pageable pageable);

    Slice<Payment> findByMerchantIdAndOrder_IdAndStatusOrderByCreatedAtDesc(
            UUID merchantId, UUID orderId, PaymentStatus status, Pageable pageable);

    Optional<Payment> findByIdAndMerchantId(UUID paymentId, UUID merchantId);

    List<Payment> findTop500ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(PaymentStatus paymentStatus, LocalDateTime globalWindow);

    // For the timeout sweeper: the oldest payments still in a state they should have left by now.
    List<Payment> findTop200ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(PaymentStatus paymentStatus, LocalDateTime before);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :paymentId and p.merchantId = :merchantId")
    Optional<Payment> findByIdAndMerchantIdForUpdate(UUID paymentId, UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :paymentId")
    Optional<Payment> findByIdForUpdate(UUID paymentId);

    // What settlement may pay out: captured (or partly refunded), not yet paid out, captured long enough ago,
    // and with no refund still waiting on the bank (paying out money that is about to be refunded).
    @Query("select p from Payment p where p.merchantId = :merchantId and p.status in :statuses and p.settledAt is null "
            + "and p.capturedAt < :capturedBefore "
            + "and not exists (select r.id from Refund r where r.payment = p and r.status in :waitingRefunds) "
            + "order by p.createdAt asc, p.id asc")
    Slice<Payment> findSettleable(UUID merchantId, Collection<PaymentStatus> statuses, LocalDateTime capturedBefore,
                                  Collection<com.project.payflo.common_lib.enums.RefundStatus> waitingRefunds, Pageable pageable);

    // Locked in id order: two transactions locking overlapping sets (a settlement and a capture batch) take them
    // in the same order, so they wait for each other instead of deadlocking.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id in :ids order by p.id")
    List<Payment> findAllByIdForUpdate(Collection<UUID> ids);

    Optional<Payment> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    boolean existsByOrder_IdAndStatusIn(UUID orderId, Collection<PaymentStatus> statuses);

    // Every attempt on the order with this method, whatever became of it (card-testing protection caps them).
    long countByOrder_IdAndMethod(UUID orderId, PaymentMethod method);

    // ---- Analytics (AnalyticsService). Native, because they group by calendar day. All are scoped by merchant,
    // read-only, and bounded by a date range, so they stay on the (merchant_id, captured_at / created_at) indexes.

    // Revenue: payments captured per day (day, count, sum of amount_units).
    @Query(value = "select cast(captured_at as date), count(*), coalesce(sum(amount_units), 0) from payment "
            + "where merchant_id = :merchantId and captured_at >= :from and captured_at < :to group by 1 order by 1",
            nativeQuery = true)
    List<Object[]> capturedByDay(UUID merchantId, LocalDateTime from, LocalDateTime to);

    // Outcomes: payments started per day and status (day, status, count).
    @Query(value = "select cast(created_at as date), status, count(*) from payment "
            + "where merchant_id = :merchantId and created_at >= :from and created_at < :to group by 1, 2",
            nativeQuery = true)
    List<Object[]> createdByDayAndStatus(UUID merchantId, LocalDateTime from, LocalDateTime to);

    // Captured payments by method (method name, count, sum of amount_units), biggest first.
    @Query(value = "select method, count(*), coalesce(sum(amount_units), 0) from payment "
            + "where merchant_id = :merchantId and captured_at >= :from and captured_at < :to group by method order by 3 desc",
            nativeQuery = true)
    List<Object[]> capturedByMethod(UUID merchantId, LocalDateTime from, LocalDateTime to);
}
