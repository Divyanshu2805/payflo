package com.project.payflo.common_lib.outbox;

import com.project.payflo.common_lib.enums.OutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@RequiredArgsConstructor
public class OutboxResultHandler {

    private static final int MAX_ATTEMPTS = 3;

    private final OutboxEventRepository outboxEventRepository;

    // One transaction and one UPDATE for a whole acknowledged batch, instead of one per event.
    @Transactional
    public void handleEventsPublished(List<OutboxEvent> events) {
        outboxEventRepository.markPublished(events.stream().map(OutboxEvent::getId).toList(), LocalDateTime.now());
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
