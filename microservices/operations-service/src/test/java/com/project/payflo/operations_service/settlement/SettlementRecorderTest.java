package com.project.payflo.operations_service.settlement;

import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.EventAggregateType;
import com.project.payflo.common_lib.enums.SettlementStatus;
import com.project.payflo.operations_service.entity.Settlement;
import com.project.payflo.operations_service.entity.SettlementPayment;
import com.project.payflo.operations_service.outbox.OutboxEventPublisher;
import com.project.payflo.operations_service.repository.SettlementPaymentRepository;
import com.project.payflo.operations_service.repository.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementRecorderTest {

    private final SettlementRepository settlementRepository = mock(SettlementRepository.class);
    private final SettlementPaymentRepository settlementPaymentRepository = mock(SettlementPaymentRepository.class);
    private final OutboxEventPublisher events = mock(OutboxEventPublisher.class);
    private final SettlementRecorder recorder = new SettlementRecorder(settlementRepository, settlementPaymentRepository, events);

    private Settlement settlement;

    @BeforeEach
    void aSettlement() {
        settlement = Settlement.builder().id(UUID.randomUUID()).merchantId(UUID.randomUUID())
                .netAmount(Money.inr(9_000)).status(SettlementStatus.INITIATED).build();
        when(settlementRepository.findByIdForUpdate(settlement.getId())).thenReturn(Optional.of(settlement));
        when(settlementRepository.findById(settlement.getId())).thenReturn(Optional.of(settlement));
    }

    private Settlement inStatus(SettlementStatus status) {
        settlement.setStatus(status);
        return settlement;
    }

    @Test
    @SuppressWarnings("unchecked")
    void creatingASettlementRecordsItAndEveryPaymentItCovers() {
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> {
            Settlement s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
        List<UUID> payments = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        Settlement created = recorder.createInitiated(UUID.randomUUID(), Money.inr(10_000), Money.inr(0),
                Money.inr(200), Money.inr(36), Money.inr(9_764), payments);

        assertThat(created.getStatus()).isEqualTo(SettlementStatus.INITIATED);
        ArgumentCaptor<List<SettlementPayment>> links = ArgumentCaptor.forClass(List.class);
        verify(settlementPaymentRepository).saveAll(links.capture());
        assertThat(links.getValue()).extracting(l -> l.getId().getPaymentId()).containsExactlyElementsOf(payments);
        assertThat(links.getValue()).allSatisfy(l -> assertThat(l.getId().getSettlementId()).isEqualTo(created.getId()));
    }

    @Test
    void anAcceptedTransferMovesAnInitiatedSettlementToPending() {
        recorder.markTransferPending(settlement.getId(), "TXN_1");

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.TRANSFER_PENDING);
        assertThat(settlement.getBankReference()).isEqualTo("TXN_1");
    }

    @Test
    void aRepeatedAcceptanceDoesNothing() {
        inStatus(SettlementStatus.PROCESSED);

        recorder.markTransferPending(settlement.getId(), "TXN_2");

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PROCESSED);
        assertThat(settlement.getBankReference()).isNull();
    }

    @Test
    void aTransferThatCouldNotBeStartedFailsTheSettlementOnlyWhileInitiated() {
        recorder.markFailed(settlement.getId(), "boom");
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.FAILED);

        inStatus(SettlementStatus.TRANSFER_PENDING);
        recorder.markFailed(settlement.getId(), "late");
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.TRANSFER_PENDING);
    }

    @Test
    void theBanksYesProcessesAPendingSettlementOnceAndPublishesIt() {
        inStatus(SettlementStatus.TRANSFER_PENDING);

        Optional<Settlement> processed = recorder.markProcessed(settlement.getId());

        assertThat(processed).isPresent();
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PROCESSED);
        assertThat(settlement.getProcessedAt()).isNotNull();
        assertThat(settlement.getPaymentsSettledAt()).isNull(); // not finished until the payments are marked

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq(EventAggregateType.SETTLEMENT), eq(settlement.getId()), eq("SETTLEMENT_PROCESSED"), payload.capture());
        // the id, not the whole entity (which used to be serialized into the event)
        assertThat(payload.getValue().get("settlementId")).isEqualTo(settlement.getId().toString());
        assertThat(payload.getValue().get("merchantId")).isEqualTo(settlement.getMerchantId().toString());
    }

    @Test
    void aSettlementThatIsNoLongerPendingIsNotProcessedTwice() {
        inStatus(SettlementStatus.PROCESSED);

        assertThat(recorder.markProcessed(settlement.getId())).isEmpty();
        verify(events, never()).publish(any(), any(), any(), anyMap());
    }

    @Test
    void theBanksNoFailsAPendingSettlementAndPublishesIt() {
        inStatus(SettlementStatus.TRANSFER_PENDING);

        recorder.markBankFailed(settlement.getId(), "ACCOUNT_CLOSED", "closed");

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(settlement.getFailureReason()).contains("ACCOUNT_CLOSED");
        verify(events).publish(any(), eq(settlement.getId()), eq("SETTLEMENT_FAILED"), anyMap());
    }

    @Test
    void markingPaymentsSettledKeepsTheFirstTimestamp() {
        recorder.markPaymentsSettled(settlement.getId());
        var first = settlement.getPaymentsSettledAt();
        assertThat(first).isNotNull();

        recorder.markPaymentsSettled(settlement.getId());

        assertThat(settlement.getPaymentsSettledAt()).isEqualTo(first);
    }

    @Test
    void aTransferThatNeverStartedTellsTheMerchantTheSettlementFailed() {
        recorder.markFailed(settlement.getId(), "TRANSFER_NOT_STARTED : BankTransferRefusedException");

        verify(events).publish(any(), eq(settlement.getId()), eq("SETTLEMENT_FAILED"), anyMap());
    }

    @Test
    void aLateFailureOfASettlementThatMovedOnPublishesNothing() {
        inStatus(SettlementStatus.PROCESSED);

        recorder.markFailed(settlement.getId(), "late");

        verify(events, never()).publish(any(), any(), any(), anyMap());
    }
}
