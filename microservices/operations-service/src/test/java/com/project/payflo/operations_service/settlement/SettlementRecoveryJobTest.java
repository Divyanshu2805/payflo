package com.project.payflo.operations_service.settlement;

import com.project.payflo.common_lib.dto.SettlementBankDetails;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.SettlementStatus;
import com.project.payflo.operations_service.entity.Settlement;
import com.project.payflo.operations_service.repository.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementRecoveryJobTest {

    private final SettlementRepository settlementRepository = mock(SettlementRepository.class);
    private final SettlementTransactionExecutor executor = mock(SettlementTransactionExecutor.class);
    private final SettlementIntegrationGateway gateway = mock(SettlementIntegrationGateway.class);
    private final SettlementRecoveryJob job = new SettlementRecoveryJob(settlementRepository, executor, gateway, new SettlementProperties());

    private final SettlementBankDetails bank = new SettlementBankDetails("123456789012", "HDFC0001234", "Holder");

    @BeforeEach
    void nothingStuckByDefault() {
        when(settlementRepository.findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());
        when(settlementRepository.findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(any(), any())).thenReturn(List.of());
        when(settlementRepository.findTop100ByStatusAndPaymentsSettledAtIsNullAndProcessedAtBeforeOrderByProcessedAtAsc(any(), any()))
                .thenReturn(List.of());
    }

    private Settlement settlement(SettlementStatus status) {
        return Settlement.builder().id(UUID.randomUUID()).merchantId(UUID.randomUUID())
                .netAmount(Money.inr(9_000)).status(status).build();
    }

    @Test
    void aSettlementThatNeverReachedTheBankHasItsTransferStartedAgain() {
        Settlement stuck = settlement(SettlementStatus.INITIATED);
        when(settlementRepository.findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(eq(SettlementStatus.INITIATED), any()))
                .thenReturn(List.of(stuck));
        when(gateway.getSettlementBankDetails(stuck.getMerchantId())).thenReturn(bank);

        job.recover();

        verify(executor).startTransfer(eq(stuck.getId()), eq(stuck.getMerchantId()), eq(stuck.getNetAmount()), eq(bank), any(LocalDate.class));
    }

    @Test
    void aPaidOutSettlementWhosePaymentsWereNeverMarkedIsFinished() {
        Settlement unfinished = settlement(SettlementStatus.PROCESSED);
        when(settlementRepository.findTop100ByStatusAndPaymentsSettledAtIsNullAndProcessedAtBeforeOrderByProcessedAtAsc(
                eq(SettlementStatus.PROCESSED), any())).thenReturn(List.of(unfinished));

        job.recover();

        verify(executor).finishPaymentMarking(unfinished.getId());
    }

    @Test
    void onlySettlementsOlderThanTheGracePeriodAreTouched() {
        job.recover();

        var cutoff = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(settlementRepository).findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(eq(SettlementStatus.INITIATED), cutoff.capture());
        org.assertj.core.api.Assertions.assertThat(cutoff.getValue()).isBefore(LocalDateTime.now().minusMinutes(1));
    }

    @Test
    void oneSettlementThatCantBeRecoveredDoesNotStopTheRest() {
        Settlement broken = settlement(SettlementStatus.INITIATED);
        Settlement fine = settlement(SettlementStatus.INITIATED);
        when(settlementRepository.findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(eq(SettlementStatus.INITIATED), any()))
                .thenReturn(List.of(broken, fine));
        when(gateway.getSettlementBankDetails(broken.getMerchantId())).thenThrow(new RuntimeException("merchant-service down"));
        when(gateway.getSettlementBankDetails(fine.getMerchantId())).thenReturn(bank);

        job.recover();

        verify(executor, never()).startTransfer(eq(broken.getId()), any(), any(), any(), any());
        verify(executor).startTransfer(eq(fine.getId()), any(), any(), eq(bank), any());
    }

    @Test
    void aFailureMarkingPaymentsDoesNotAbortTheRun() {
        Settlement first = settlement(SettlementStatus.PROCESSED);
        Settlement second = settlement(SettlementStatus.PROCESSED);
        when(settlementRepository.findTop100ByStatusAndPaymentsSettledAtIsNullAndProcessedAtBeforeOrderByProcessedAtAsc(
                eq(SettlementStatus.PROCESSED), any())).thenReturn(List.of(first, second));
        doThrow(new RuntimeException("payment-service down")).when(executor).finishPaymentMarking(first.getId());

        job.recover();

        verify(executor).finishPaymentMarking(second.getId());
    }

    @Test
    void aTransferTheBankNeverAnsweredIsFailedSoItsPaymentsArePaidOutAgain() {
        Settlement unanswered = settlement(SettlementStatus.TRANSFER_PENDING);
        when(settlementRepository.findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(SettlementStatus.TRANSFER_PENDING), any()))
                .thenReturn(List.of(unanswered));

        job.recover();

        verify(executor).resolveTransfer(eq(unanswered.getId()), eq("TRANSFER_TIMEOUT"), any());
    }

    @Test
    void aTransferIsOnlyGivenUpOnAfterTheConfiguredTimeout() {
        job.recover();

        var cutoff = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(settlementRepository).findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(SettlementStatus.TRANSFER_PENDING), cutoff.capture());
        org.assertj.core.api.Assertions.assertThat(cutoff.getValue())
                .isBefore(LocalDateTime.now().minusMinutes(119))
                .isAfter(LocalDateTime.now().minusMinutes(121));
    }

    @Test
    void oneUnanswerableTransferDoesNotStopTheRest() {
        Settlement broken = settlement(SettlementStatus.TRANSFER_PENDING);
        Settlement fine = settlement(SettlementStatus.TRANSFER_PENDING);
        when(settlementRepository.findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(SettlementStatus.TRANSFER_PENDING), any()))
                .thenReturn(List.of(broken, fine));
        doThrow(new RuntimeException("db down")).when(executor).resolveTransfer(eq(broken.getId()), any(), any());

        job.recover();

        verify(executor).resolveTransfer(eq(fine.getId()), eq("TRANSFER_TIMEOUT"), any());
    }
}
