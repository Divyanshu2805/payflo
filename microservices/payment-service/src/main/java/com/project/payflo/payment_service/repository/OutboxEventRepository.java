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
}
