package com.project.payflo.operations_service.repository;

import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.operations_service.entity.WebhookEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WebhookEvent e where e.id = :id")
    Optional<WebhookEvent> findByIdForUpdate(UUID id);
}
