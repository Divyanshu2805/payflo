package com.project.payflo.operations_service.repository;

import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.operations_service.entity.WebhookEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    // Oldest-first and bounded, so a backlog is worked through in slices.
    List<WebhookEvent> findTop500ByStatusInAndNextRetryAtBeforeOrderByNextRetryAtAsc(
            Collection<WebhookEventStatus> statuses, LocalDateTime before);

    Slice<WebhookEvent> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Slice<WebhookEvent> findByMerchantIdAndStatusOrderByCreatedAtDesc(UUID merchantId, WebhookEventStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WebhookEvent e where e.id = :id")
    Optional<WebhookEvent> findByIdForUpdate(UUID id);

    // A batch of events locked together, in id order so two batches can't deadlock (WebhookDeliveryRecorder.claimAll).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WebhookEvent e where e.id in :ids order by e.id")
    List<WebhookEvent> findAllByIdForUpdate(Collection<UUID> ids);

    // One UPDATE for many events the merchant's endpoint accepted, instead of a lock and an UPDATE for each.
    @Modifying
    @Query("update WebhookEvent e set e.status = com.project.payflo.common_lib.enums.WebhookEventStatus.DELIVERED, "
            + "e.deliveredAt = :now, e.updatedAt = :now, e.lastResponseCode = :statusCode, e.nextRetryAt = null "
            + "where e.id in :ids")
    int markDelivered(Collection<UUID> ids, Integer statusCode, LocalDateTime now);

    long countByStatus(WebhookEventStatus status);

    // When the oldest event that hasn't had a first attempt became due (WebhookMetrics). Empty when none is waiting.
    @Query("select min(e.nextRetryAt) from WebhookEvent e where e.status = :status and e.attempts = 0")
    Optional<LocalDateTime> oldestUnattemptedDueAt(WebhookEventStatus status);
}
