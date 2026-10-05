package com.project.payflo.operations_service.webhook;

import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.operations_service.entity.WebhookEvent;
import com.project.payflo.operations_service.repository.WebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookDeliverySchedulerTest {

    private final WebhookRetryQueue retryQueue = mock(WebhookRetryQueue.class);
    private final WebhookEventRepository repository = mock(WebhookEventRepository.class);
    private final WebhookDeliveryScheduler scheduler =
            new WebhookDeliveryScheduler(retryQueue, repository, mock(WebhookDeliverExecutor.class));

    @Test
    @SuppressWarnings("unchecked")
    void reconcilesPendingAndFailedEventsThatAreOverdueNotJustPendingOnes() {
        WebhookEvent failed = WebhookEvent.builder().id(UUID.randomUUID()).status(WebhookEventStatus.FAILED)
                .nextRetryAt(LocalDateTime.now().minusHours(1)).build();
        ArgumentCaptor<Collection<WebhookEventStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<LocalDateTime> before = ArgumentCaptor.forClass(LocalDateTime.class);
        when(repository.findTop500ByStatusInAndNextRetryAtBeforeOrderByNextRetryAtAsc(any(), any()))
                .thenReturn(List.of(failed));

        scheduler.reconcileFromDatabase();

        verify(repository).findTop500ByStatusInAndNextRetryAtBeforeOrderByNextRetryAtAsc(statuses.capture(), before.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(WebhookEventStatus.PENDING, WebhookEventStatus.FAILED);
        // only events overdue by a grace period, so one being delivered right now (it holds a lease) is left alone
        assertThat(before.getValue()).isBefore(LocalDateTime.now());
        verify(retryQueue).enqueueIfAbsent(failed.getId(), failed.getNextRetryAt());
    }
}
