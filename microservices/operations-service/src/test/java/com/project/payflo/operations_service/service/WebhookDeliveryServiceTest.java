package com.project.payflo.operations_service.service;

import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.operations_service.dto.WebhookDeliveryResponse;
import com.project.payflo.operations_service.entity.DlqEvent;
import com.project.payflo.operations_service.entity.WebhookEvent;
import com.project.payflo.operations_service.repository.DlqEventRepository;
import com.project.payflo.operations_service.repository.WebhookEventRepository;
import com.project.payflo.operations_service.webhook.WebhookRetryQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.SliceImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookDeliveryServiceTest {

    private final WebhookEventRepository repository = mock(WebhookEventRepository.class);
    private final DlqEventRepository dlqRepository = mock(DlqEventRepository.class);
    private final WebhookRetryQueue retryQueue = mock(WebhookRetryQueue.class);
    private final WebhookDeliveryService service = new WebhookDeliveryService(repository, dlqRepository, retryQueue);

    private final UUID merchantId = UUID.randomUUID();
    private WebhookEvent event;

    @BeforeEach
    void aDeadDelivery() {
        event = WebhookEvent.builder().id(UUID.randomUUID()).merchantId(merchantId).eventType("PAYMENT_STATUS_CHANGED")
                .eventId("evt-1").targetUrl("https://example.com/hook").signature("secret-signature")
                .payload(Map.of("k", "v")).status(WebhookEventStatus.DEAD).attempts(7)
                .nextRetryAt(null).build();
        when(repository.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
        when(repository.findById(event.getId())).thenReturn(Optional.of(event));
    }

    @Test
    void aDeadDeliveryIsReplayedFromTheStartAndQueuedAtOnce() {
        DlqEvent dlq = DlqEvent.builder().merchantId(merchantId).build();
        when(dlqRepository.findByWebhookEvent_Id(event.getId())).thenReturn(Optional.of(dlq));

        WebhookDeliveryResponse response = service.replay(merchantId, event.getId());

        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PENDING);
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getNextRetryAt()).isBeforeOrEqualTo(LocalDateTime.now());
        assertThat(dlq.getReplayedAt()).isNotNull();
        assertThat(response.status()).isEqualTo(WebhookEventStatus.PENDING);
        verify(retryQueue).enqueue(eq(event.getId()), any(LocalDateTime.class));
    }

    @Test
    void aFailedOrDeliveredDeliveryCanBeReplayedToo() {
        for (WebhookEventStatus status : new WebhookEventStatus[]{WebhookEventStatus.FAILED, WebhookEventStatus.DELIVERED}) {
            event.setStatus(status);
            event.setDeliveredAt(LocalDateTime.now());

            service.replay(merchantId, event.getId());

            assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PENDING);
            assertThat(event.getDeliveredAt()).isNull();
        }
    }

    @Test
    void aDeliveryThatHasNotBeenAttemptedYetIsAlreadyOnItsWay() {
        event.setStatus(WebhookEventStatus.PENDING);

        assertThatThrownBy(() -> service.replay(merchantId, event.getId()))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("WEBHOOK_DELIVERY_PENDING"));
        verify(retryQueue, never()).enqueue(any(), any());
    }

    @Test
    void anotherMerchantsDeliveryIsNotFoundAndIsLeftUntouched() {
        assertThatThrownBy(() -> service.replay(UUID.randomUUID(), event.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.DEAD);
    }

    @Test
    void ifRedisIsDownTheReplayStillSucceedsAndTheReconcilerPicksItUp() {
        doThrow(new RuntimeException("redis down")).when(retryQueue).enqueue(any(), any());

        assertThatCode(() -> service.replay(merchantId, event.getId())).doesNotThrowAnyException();
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PENDING);
    }

    @Test
    void aDeliveryIsReadOnlyByItsOwnMerchant() {
        assertThat(service.get(merchantId, event.getId()).eventId()).isEqualTo("evt-1");
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), event.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theSignatureIsNeverReturned() {
        assertThat(WebhookDeliveryResponse.class.getRecordComponents())
                .extracting(c -> c.getName())
                .doesNotContain("signature", "requestBody");
    }

    @Test
    void listingFiltersByStatus() {
        when(repository.findByMerchantIdAndStatusOrderByCreatedAtDesc(eq(merchantId), eq(WebhookEventStatus.DEAD), any()))
                .thenReturn(new SliceImpl<>(List.of(event)));

        PageResponse<WebhookDeliveryResponse> page = service.list(merchantId, WebhookEventStatus.DEAD, 0, 20);

        assertThat(page.items()).hasSize(1);
        verify(repository, never()).findByMerchantIdOrderByCreatedAtDesc(any(), any());
    }
}
