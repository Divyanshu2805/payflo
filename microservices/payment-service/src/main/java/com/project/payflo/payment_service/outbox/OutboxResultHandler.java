package com.project.payflo.payment_service.outbox;

import com.project.payflo.common_lib.enums.OutboxStatus;
import com.project.payflo.payment_service.entity.OutboxEvent;
import com.project.payflo.payment_service.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OutboxResultHandler {

    private final OutboxEventRepository outboxEventRepository;
    private final Integer MAX_ATTEMPTS = 3;

    @Transactional
    public void handleEventPublished(OutboxEvent event) {
        event.setStatus(OutboxStatus.PUBLISHED);
        event.setPublishedAt(LocalDateTime.now());
        outboxEventRepository.save(event);
    }

    // One transaction (and JDBC-batched UPDATEs) for a whole acknowledged batch, instead of one
    // transaction per event.
    @Transactional
    public void handleEventsPublished(List<OutboxEvent> events) {
        LocalDateTime now = LocalDateTime.now();
        for (OutboxEvent event : events) {
            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(now);
        }
        outboxEventRepository.saveAll(events);
    }

    @Transactional
    public void handleEventFailed(OutboxEvent event, String errorMessage) {
        event.setAttempts(event.getAttempts()+1);
        event.setLastError(
                errorMessage.length() < 1000 ? errorMessage: errorMessage.substring(0, 1000));
        if (event.getAttempts() >= MAX_ATTEMPTS) {
            event.setStatus(OutboxStatus.FAILED);
        }
        outboxEventRepository.save(event);
    }
}








