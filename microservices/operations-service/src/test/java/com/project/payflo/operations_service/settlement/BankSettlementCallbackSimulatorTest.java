package com.project.payflo.operations_service.settlement;

import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.ChaosMode;
import com.project.payflo.common_lib.enums.SettlementStatus;
import com.project.payflo.operations_service.entity.Settlement;
import com.project.payflo.operations_service.repository.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

class BankSettlementCallbackSimulatorTest {

    private final SettlementRepository settlementRepository = mock(SettlementRepository.class);
    private final SettlementTransactionExecutor executor = mock(SettlementTransactionExecutor.class);
    private final PayoutSimulatorProperties properties = new PayoutSimulatorProperties();
    private final BankSettlementCallbackSimulator simulator =
            new BankSettlementCallbackSimulator(settlementRepository, executor, new PayoutOutcomeDecider(properties));

    @BeforeEach
    void aFastBank() {
        properties.setMinDelaySeconds(1);
        properties.setMaxDelaySeconds(1);
    }

    private Settlement pending(LocalDateTime acceptedAt) {
        Settlement settlement = Settlement.builder().id(UUID.randomUUID()).merchantId(UUID.randomUUID())
                .netAmount(Money.inr(9_000)).status(SettlementStatus.TRANSFER_PENDING).build();
        settlement.setUpdatedAt(acceptedAt);
        return settlement;
    }

    private void bankHolds(Settlement... settlements) {
        when(settlementRepository.findByStatus(SettlementStatus.TRANSFER_PENDING)).thenReturn(List.of(settlements));
    }

    @Test
    void aSuccessfulTransferIsResolvedWithNoError() {
        properties.setChaosMode(ChaosMode.SUCCESS);
        Settlement settlement = pending(LocalDateTime.now().minusMinutes(1));
        bankHolds(settlement);

        simulator.processCallbacks();

        verify(executor).resolveTransfer(settlement.getId(), null, null);
    }

    @Test
    void aDeclinedTransferIsResolvedWithTheBanksErrorSoThePayoutFails() {
        properties.setChaosMode(ChaosMode.FAILURE);
        Settlement settlement = pending(LocalDateTime.now().minusMinutes(1));
        bankHolds(settlement);

        simulator.processCallbacks();

        verify(executor).resolveTransfer(eq(settlement.getId()), eq("SIM_PAYOUT_DECLINED"), any());
    }

    @Test
    void aTransferStillWithinTheBanksDelayIsLeftAlone() {
        properties.setChaosMode(ChaosMode.SUCCESS);
        bankHolds(pending(LocalDateTime.now()));

        simulator.processCallbacks();

        verify(executor, never()).resolveTransfer(any(), any(), any());
    }

    @Test
    void aBankThatNeverAnswersLeavesTheTransferPending() {
        properties.setChaosMode(ChaosMode.TIMEOUT);
        bankHolds(pending(LocalDateTime.now().minusDays(1)));

        simulator.processCallbacks();

        verify(executor, never()).resolveTransfer(any(), any(), any());
    }

    @Test
    void oneSettlementFailingToResolveDoesNotHoldUpTheRest() {
        properties.setChaosMode(ChaosMode.SUCCESS);
        Settlement broken = pending(LocalDateTime.now().minusMinutes(1));
        Settlement fine = pending(LocalDateTime.now().minusMinutes(1));
        bankHolds(broken, fine);
        doThrow(new RuntimeException("payment-service down")).when(executor).resolveTransfer(broken.getId(), null, null);

        simulator.processCallbacks();

        verify(executor).resolveTransfer(fine.getId(), null, null);
    }
}
