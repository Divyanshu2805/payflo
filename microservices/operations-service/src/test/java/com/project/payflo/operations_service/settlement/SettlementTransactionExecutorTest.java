package com.project.payflo.operations_service.settlement;

import com.project.payflo.common_lib.dto.PaymentSettlementView;
import com.project.payflo.common_lib.dto.SettlementBankDetails;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.operations_service.entity.Settlement;
import com.project.payflo.operations_service.repository.SettlementPaymentRepository;
import com.project.payflo.operations_service.settlement.dto.BankTransferResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementTransactionExecutorTest {

    private final SettlementPaymentRepository settlementPaymentRepository = mock(SettlementPaymentRepository.class);
    private final BankTransferProcessor bank = mock(BankTransferProcessor.class);
    private final SettlementIntegrationGateway gateway = mock(SettlementIntegrationGateway.class);
    private final SettlementRecorder recorder = mock(SettlementRecorder.class);
    private final SettlementProperties properties = new SettlementProperties();

    private final SettlementTransactionExecutor executor = new SettlementTransactionExecutor(
            settlementPaymentRepository, bank, gateway, recorder, properties);

    private final UUID merchantId = UUID.randomUUID();

    @BeforeEach
    void aMerchantWithABankAccount() {
        when(gateway.getSettlementBankDetails(merchantId))
                .thenReturn(new SettlementBankDetails("123456789012", "HDFC0001234", "Holder"));
        when(settlementPaymentRepository.findPaymentIdsInSettlements(eq(merchantId), anyCollection()))
                .thenReturn(Set.of());
        when(recorder.createInitiated(any(), any(), any(), any(), any(), any(), anyList()))
                .thenAnswer(inv -> Settlement.builder().id(UUID.randomUUID()).merchantId(merchantId).build());
        when(bank.initiate(any(), any(), any(), anyString(), anyString())).thenReturn(new BankTransferResult("TXN_1"));
    }

    private void paymentsAre(PaymentSettlementView... payments) {
        when(gateway.findUnsettledCaptured(eq(merchantId), any(), eq(0), anyInt())).thenReturn(List.of(payments));
    }

    private static PaymentSettlementView payment(int units, String currency) {
        return new PaymentSettlementView(UUID.randomUUID(), units, 0, currency);
    }

    private static PaymentSettlementView refunded(int units, int refundedUnits) {
        return new PaymentSettlementView(UUID.randomUUID(), units, refundedUnits, "INR");
    }

    private List<Money> nets(int expectedSettlements) {
        ArgumentCaptor<Money> net = ArgumentCaptor.forClass(Money.class);
        verify(recorder, times(expectedSettlements))
                .createInitiated(eq(merchantId), any(), any(), any(), any(), net.capture(), anyList());
        return net.getAllValues();
    }

    // ---- amounts

    @Test
    void settlesCapturedPaymentsAndTakesFeeAndGst() {
        paymentsAre(payment(60_000, "INR"), payment(40_000, "INR"));

        executor.processForMerchant(merchantId, LocalDate.now());

        // gross 100,000 - fee 2,000 - GST 360 = 97,640
        assertThat(nets(1)).singleElement().satisfies(m -> assertThat(m.getAmountUnits()).isEqualTo(97_640));
    }

    @Test
    void refundsAreDeductedAndTheFeeIsOnlyChargedOnWhatWasKept() {
        paymentsAre(refunded(100_000, 10_000));

        executor.processForMerchant(merchantId, LocalDate.now());

        ArgumentCaptor<Money> gross = ArgumentCaptor.forClass(Money.class);
        ArgumentCaptor<Money> refunds = ArgumentCaptor.forClass(Money.class);
        ArgumentCaptor<Money> fee = ArgumentCaptor.forClass(Money.class);
        ArgumentCaptor<Money> net = ArgumentCaptor.forClass(Money.class);
        verify(recorder).createInitiated(eq(merchantId), gross.capture(), refunds.capture(), fee.capture(), any(),
                net.capture(), anyList());
        assertThat(gross.getValue().getAmountUnits()).isEqualTo(100_000);
        assertThat(refunds.getValue().getAmountUnits()).isEqualTo(10_000);
        // kept 90,000: fee 1,800, GST 324, net 87,876
        assertThat(fee.getValue().getAmountUnits()).isEqualTo(1_800);
        assertThat(net.getValue().getAmountUnits()).isEqualTo(87_876);
    }

    @Test
    void theFeeAndGstRatesComeFromConfiguration() {
        properties.setFeeRate(0.10);
        properties.setGstRate(0.0);
        paymentsAre(payment(10_000, "INR"));

        executor.processForMerchant(merchantId, LocalDate.now());

        assertThat(nets(1)).singleElement().satisfies(m -> assertThat(m.getAmountUnits()).isEqualTo(9_000));
    }

    @Test
    void aTotalBeyondAnIntIsSplitInsteadOfOverflowing() {
        paymentsAre(payment(1_500_000_000, "INR"), payment(1_500_000_000, "INR"));

        executor.processForMerchant(merchantId, LocalDate.now());

        assertThat(nets(2)).allSatisfy(m -> assertThat(m.getAmountUnits()).isPositive());
    }

    @Test
    void currenciesAreNeverAddedTogether() {
        paymentsAre(payment(10_000, "INR"), payment(10_000, "USD"), payment(5_000, "INR"));

        executor.processForMerchant(merchantId, LocalDate.now());

        assertThat(nets(2)).extracting(Money::getCurrency).containsExactlyInAnyOrder("INR", "USD");
    }

    // ---- which payments

    @Test
    void paymentsAlreadyInAPayoutInFlightAreLeftOut() {
        PaymentSettlementView alreadyPaidOut = payment(50_000, "INR");
        PaymentSettlementView fresh = payment(10_000, "INR");
        paymentsAre(alreadyPaidOut, fresh);
        when(settlementPaymentRepository.findPaymentIdsInSettlements(eq(merchantId), anyCollection()))
                .thenReturn(Set.of(alreadyPaidOut.paymentId()));

        executor.processForMerchant(merchantId, LocalDate.now());

        // only the fresh 10,000 is paid: 10,000 - 200 - 36
        assertThat(nets(1)).singleElement().satisfies(m -> assertThat(m.getAmountUnits()).isEqualTo(9_764));
    }

    @Test
    void nothingIsPaidWhenEveryPaymentIsAlreadyInFlight() {
        PaymentSettlementView p = payment(50_000, "INR");
        paymentsAre(p);
        when(settlementPaymentRepository.findPaymentIdsInSettlements(eq(merchantId), anyCollection()))
                .thenReturn(Set.of(p.paymentId()));

        executor.processForMerchant(merchantId, LocalDate.now());

        verify(recorder, never()).createInitiated(any(), any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void theHoldIsPassedAsACaptureCutoff() {
        properties.setHoldDays(2);
        paymentsAre();

        executor.processForMerchant(merchantId, LocalDate.now());

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(gateway).findUnsettledCaptured(eq(merchantId), cutoff.capture(), eq(0), anyInt());
        assertThat(cutoff.getValue()).isBefore(LocalDateTime.now().minusDays(2).plusMinutes(1));
        assertThat(cutoff.getValue()).isAfter(LocalDateTime.now().minusDays(2).minusMinutes(1));
    }

    @Test
    void aLongListIsReadPageByPage() {
        properties.setPageSize(2);
        when(gateway.findUnsettledCaptured(eq(merchantId), any(), eq(0), eq(2)))
                .thenReturn(List.of(payment(1_000, "INR"), payment(1_000, "INR")));
        when(gateway.findUnsettledCaptured(eq(merchantId), any(), eq(1), eq(2)))
                .thenReturn(List.of(payment(1_000, "INR")));

        executor.processForMerchant(merchantId, LocalDate.now());

        ArgumentCaptor<List<UUID>> ids = ArgumentCaptor.forClass(List.class);
        verify(recorder).createInitiated(eq(merchantId), any(), any(), any(), any(), any(), ids.capture());
        assertThat(ids.getValue()).hasSize(3);
    }

    @Test
    void thePerRunCapStopsTheReading() {
        properties.setPageSize(2);
        properties.setMaxPaymentsPerRun(2);
        when(gateway.findUnsettledCaptured(eq(merchantId), any(), eq(0), eq(2)))
                .thenReturn(List.of(payment(1_000, "INR"), payment(1_000, "INR")));

        executor.processForMerchant(merchantId, LocalDate.now());

        verify(gateway, never()).findUnsettledCaptured(eq(merchantId), any(), eq(1), anyInt());
    }

    // ---- merchants that can't be paid

    @Test
    void aMerchantWithoutABankAccountIsSkippedAndNothingIsWritten() {
        paymentsAre(payment(10_000, "INR"));
        when(gateway.getSettlementBankDetails(merchantId)).thenReturn(new SettlementBankDetails(null, null, null));

        executor.processForMerchant(merchantId, LocalDate.now());

        verify(recorder, never()).createInitiated(any(), any(), any(), any(), any(), any(), anyList());
        verify(bank, never()).initiate(any(), any(), any(), any(), any());
    }

    @Test
    void aBlankIfscCountsAsNoBankAccount() {
        paymentsAre(payment(10_000, "INR"));
        when(gateway.getSettlementBankDetails(merchantId)).thenReturn(new SettlementBankDetails("123456789012", " ", "H"));

        executor.processForMerchant(merchantId, LocalDate.now());

        verify(bank, never()).initiate(any(), any(), any(), any(), any());
    }

    @Test
    void aFailedBankDetailsLookupSkipsTheMerchantWithoutWriting() {
        paymentsAre(payment(10_000, "INR"));
        when(gateway.getSettlementBankDetails(merchantId)).thenThrow(new RuntimeException("merchant-service down"));

        executor.processForMerchant(merchantId, LocalDate.now());

        verify(recorder, never()).createInitiated(any(), any(), any(), any(), any(), any(), anyList());
    }

    // ---- the transfer

    @Test
    void anAcceptedTransferMovesTheSettlementToPendingWithTheBanksReference() {
        paymentsAre(payment(10_000, "INR"));

        executor.processForMerchant(merchantId, LocalDate.now());

        verify(recorder).markTransferPending(any(UUID.class), eq("TXN_1"));
    }

    @Test
    void aFailedTransferMarksOnlyThatSettlementFailedSoItsPaymentsAreFreed() {
        paymentsAre(payment(10_000, "INR"));
        when(bank.initiate(any(), any(), any(), anyString(), anyString())).thenThrow(new RuntimeException("bank down"));

        executor.processForMerchant(merchantId, LocalDate.now());

        verify(recorder).markFailed(any(UUID.class), anyString());
        verify(recorder, never()).markTransferPending(any(), any());
    }

    // ---- the bank's answer

    @Test
    void aSuccessfulTransferIsProcessedThenItsPaymentsAreMarkedSettledInBatches() {
        UUID settlementId = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 1_200; i++) ids.add(UUID.randomUUID());
        when(recorder.markProcessed(settlementId)).thenReturn(Optional.of(Settlement.builder().id(settlementId).build()));
        when(recorder.paymentIds(settlementId)).thenReturn(ids);

        executor.resolveTransfer(settlementId, null, null);

        verify(gateway, times(3)).markSettled(anyList()); // 500 + 500 + 200
        verify(recorder).markPaymentsSettled(settlementId);
    }

    @Test
    void aSettlementThatIsNoLongerWaitingIsLeftAlone() {
        UUID settlementId = UUID.randomUUID();
        when(recorder.markProcessed(settlementId)).thenReturn(Optional.empty());

        executor.resolveTransfer(settlementId, null, null);

        verify(gateway, never()).markSettled(anyList());
        verify(recorder, never()).markPaymentsSettled(any());
    }

    @Test
    void whenPaymentServiceCantBeToldTheSettlementIsNotMarkedFinishedSoRecoveryRetries() {
        UUID settlementId = UUID.randomUUID();
        when(recorder.markProcessed(settlementId)).thenReturn(Optional.of(Settlement.builder().id(settlementId).build()));
        when(recorder.paymentIds(settlementId)).thenReturn(List.of(UUID.randomUUID()));
        doThrow(new RuntimeException("payment-service down")).when(gateway).markSettled(anyList());

        assertThatThrownBy(() -> executor.resolveTransfer(settlementId, null, null)).isInstanceOf(RuntimeException.class);

        verify(recorder, never()).markPaymentsSettled(any());
    }

    @Test
    void aBankFailureIsRecordedAndNothingIsMarkedSettled() {
        UUID settlementId = UUID.randomUUID();

        executor.resolveTransfer(settlementId, "INSUFFICIENT_FUNDS", "no");

        verify(recorder).markBankFailed(settlementId, "INSUFFICIENT_FUNDS", "no");
        verify(recorder, never()).markProcessed(any());
        verify(gateway, never()).markSettled(anyList());
    }
}
