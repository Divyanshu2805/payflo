package com.project.payflo.payment_service.outbox;

import com.project.payflo.payment_service.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxMaintenanceTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final OutboxMaintenance maintenance = new OutboxMaintenance(repository, transactions, 7, 5);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void runCallbacksInline() {
        when(transactions.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
    }

    @Test
    void failedEventsOlderThanThePauseAreRequeued() {
        when(repository.requeueFailed(any(), any())).thenReturn(3);

        maintenance.requeueFailed();

        var before = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).requeueFailed(before.capture(), any());
        assertThat(before.getValue()).isBefore(LocalDateTime.now().minusMinutes(4));
    }

    @Test
    void thePurgeKeepsGoingWhileBatchesAreFullAndStopsOnAPartialOne() {
        when(repository.purgePublished(any(), anyInt())).thenReturn(5_000, 5_000, 120);

        maintenance.purgePublished();

        verify(repository, times(3)).purgePublished(any(), anyInt());
    }

    @Test
    void thePurgeOnlyReachesBackPastTheRetentionPeriod() {
        when(repository.purgePublished(any(), anyInt())).thenReturn(0);

        maintenance.purgePublished();

        var before = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).purgePublished(before.capture(), anyInt());
        assertThat(before.getValue()).isBefore(LocalDateTime.now().minusDays(6));
        assertThat(before.getValue()).isAfter(LocalDateTime.now().minusDays(8));
    }

    @Test
    void anEmptyTableMeansOnePassAndNothingElse() {
        when(repository.purgePublished(any(), anyInt())).thenReturn(0);

        maintenance.purgePublished();

        verify(repository, times(1)).purgePublished(any(), anyInt());
        verify(repository, never()).requeueFailed(any(), any());
    }
}
