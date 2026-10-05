package com.project.payflo.payment_service.repository;

import com.project.payflo.common_lib.enums.OutboxStatus;
import com.project.payflo.payment_service.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop500ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    // One UPDATE for a whole acknowledged batch. saveAll() on these detached rows merged each one,
    // which is a SELECT and an UPDATE per event.
    @Modifying
    @Query("update OutboxEvent e set e.status = com.project.payflo.common_lib.enums.OutboxStatus.PUBLISHED, "
            + "e.publishedAt = :now, e.updatedAt = :now where e.id in :ids")
    int markPublished(@Param("ids") Collection<UUID> ids, @Param("now") LocalDateTime now);

    long countByStatus(OutboxStatus status);

    // A FAILED row has used up its immediate attempts. Putting it back to PENDING after a pause means a
    // Kafka outage longer than a few polls delays events instead of stranding them for good.
    @Modifying
    @Query("update OutboxEvent e set e.status = com.project.payflo.common_lib.enums.OutboxStatus.PENDING, "
            + "e.attempts = 0, e.updatedAt = :now "
            + "where e.status = com.project.payflo.common_lib.enums.OutboxStatus.FAILED and e.updatedAt < :before")
    int requeueFailed(@Param("before") LocalDateTime before, @Param("now") LocalDateTime now);

    // Bounded, and reads through idx_outbox_event_status_created_at, so a purge never scans the table.
    @Modifying
    @Query(value = "delete from outbox_event where id in (select id from outbox_event "
            + "where status = 'PUBLISHED' and created_at < :before limit :batchSize)", nativeQuery = true)
    int purgePublished(@Param("before") LocalDateTime before, @Param("batchSize") int batchSize);
}
