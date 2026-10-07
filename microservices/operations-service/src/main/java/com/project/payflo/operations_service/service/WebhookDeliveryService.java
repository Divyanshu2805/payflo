package com.project.payflo.operations_service.service;

import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.operations_service.dto.WebhookDeliveryResponse;
import com.project.payflo.operations_service.entity.WebhookEvent;
import com.project.payflo.operations_service.repository.DlqEventRepository;
import com.project.payflo.operations_service.repository.WebhookEventRepository;
import com.project.payflo.operations_service.webhook.WebhookRetryQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.UUID;

// A merchant's view of its webhook deliveries, and the way to send one again.
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDeliveryService {

    private final WebhookEventRepository webhookEventRepository;
    private final DlqEventRepository dlqEventRepository;
    private final WebhookRetryQueue webhookRetryQueue;

    @Transactional(readOnly = true)
    public PageResponse<WebhookDeliveryResponse> list(UUID merchantId, WebhookEventStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(PageResponse.clampPage(page), PageResponse.clampSize(size));
        Slice<WebhookEvent> events = status == null
                ? webhookEventRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
                : webhookEventRepository.findByMerchantIdAndStatusOrderByCreatedAtDesc(merchantId, status, pageable);
        return PageResponse.of(events, WebhookDeliveryResponse::from);
    }

    @Transactional(readOnly = true)
    public WebhookDeliveryResponse get(UUID merchantId, UUID deliveryId) {
        return webhookEventRepository.findById(deliveryId)
                .filter(e -> e.getMerchantId().equals(merchantId))
                .map(WebhookDeliveryResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("WebhookDelivery", deliveryId));
    }

    /**
     * Sends a delivery again from the start: back to PENDING with a fresh set of attempts, due now. The body
     * and signature are the ones it was first created with. Works on a dead-lettered, failed or already
     * delivered one; a delivery that has not yet had its first attempt is already on its way.
     */
    @Transactional
    public WebhookDeliveryResponse replay(UUID merchantId, UUID deliveryId) {
        WebhookEvent event = webhookEventRepository.findByIdForUpdate(deliveryId)
                .filter(e -> e.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new ResourceNotFoundException("WebhookDelivery", deliveryId));

        if (event.getStatus() == WebhookEventStatus.PENDING) {
            throw new BusinessRuleViolationException("WEBHOOK_DELIVERY_PENDING",
                    "This delivery has not been attempted yet, it is already queued");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean wasDead = event.getStatus() == WebhookEventStatus.DEAD;
        event.setStatus(WebhookEventStatus.PENDING);
        event.setAttempts(0);
        event.setNextRetryAt(now);
        event.setDeliveredAt(null);

        if (wasDead) {
            dlqEventRepository.findByWebhookEvent_Id(deliveryId).ifPresent(dlq -> dlq.setReplayedAt(now));
        }
        log.info("Webhook delivery {} replayed for merchant {}", deliveryId, merchantId);

        enqueueAfterCommit(event.getId(), now);
        return WebhookDeliveryResponse.from(event);
    }

    // Queued only once the row is committed as PENDING; if Redis is down the reconciler picks it up.
    private void enqueueAfterCommit(UUID id, LocalDateTime dueAt) {
        Runnable enqueue = () -> {
            try {
                webhookRetryQueue.enqueue(id, dueAt);
            } catch (Exception e) {
                log.warn("Could not queue replayed webhook delivery {}, the reconciler will", id, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue.run();
                }
            });
        } else {
            enqueue.run();
        }
    }
}
