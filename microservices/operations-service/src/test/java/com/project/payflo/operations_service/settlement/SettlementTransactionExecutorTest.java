package com.project.payflo.operations_service.settlement;

import com.project.payflo.common_lib.dto.PaymentSettlementView;
import com.project.payflo.common_lib.dto.SettlementBankDetails;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.SettlementStatus;
import com.project.payflo.operations_service.entity.Settlement;
import com.project.payflo.operations_service.outbox.OutboxEventPublisher;
import com.project.payflo.operations_service.repository.SettlementPaymentRepository;
import com.project.payflo.operations_service.repository.SettlementRepository;
import com.project.payflo.operations_service.settlement.dto.BankTransferResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementTransactionExecutorTest {

    private final SettlementRepository settlementRepository = mock(SettlementRepository.class);
    private final SettlementPaymentRepository settlementPaymentRepository = mock(SettlementPaymentRepository.class);
    private final BankTransferProcessor bank = mock(BankTransferProcessor.class);
    private final SettlementIntegrationGateway gateway = mock(SettlementIntegrationGateway.class);

    private final SettlementTransactionExecutor executor = new SettlementTransactionExecutor(
            settlementRepository, settlementPaymentRepository, bank, mock(OutboxEventPublisher.class), gateway);

    private final UUID merchantId = UUID.randomUUID();

    @BeforeEach
    void aMerchantWithABankAccount() {
        when(gateway.getSettlementBankDetails(merchantId))
                .thenReturn(new SettlementBankDetails("123456789012", "HDFC0001234", "Holder"));
        when(settlementPaymentRepository.findPaymentIdsInSettlements(eq(merchantId), anyCollection()))
                .thenReturn(Set.of());
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bank.initiate(any(), any(), any(), anyString(), anyString())).thenReturn(new BankTransferResult("TXN_1"));
    }

    private static PaymentSettlementView payment(int units, String currency) {
        return new PaymentSettlementView(UUID.randomUUID(), units, 0, currency);
    }

    private List<Money> transferredAmounts(int expectedTransfers) {
        ArgumentCaptor<Money> net = ArgumentCaptor.forClass(Money.class);
        verify(bank, times(expectedTransfers)).initiate(any(), eq(merchantId), net.capture(), anyString(), anyString());
        return net.getAllValues();
    }

    @Test
    void settlesCapturedPaymentsAndTakesFeeAndGst() {
        when(gateway.findUnsettledCaptured(merchantId))
                .thenReturn(List.of(payment(60_000, "INR"), payment(40_000, "INR")));

        executor.processForMerchant(merchantId, LocalDate.now());

        // gross 100,000 - fee 2,000 - GST 360 = 97,640
        assertThat(transferredAmounts(1)).singleElement()
                .satisfies(m -> assertThat(m.getAmountUnits()).isEqualTo(97_640));
    }

    @Test
    void aTotalBeyondAnIntIsSplitInsteadOfOverflowing() {
        when(gateway.findUnsettledCaptured(merchantId))
                .thenReturn(List.of(payment(1_500_000_000, "INR"), payment(1_500_000_000, "INR")));

        executor.processForMerchant(merchantId, LocalDate.now());

        assertThat(transferredAmounts(2)).allSatisfy(m -> assertThat(m.getAmountUnits()).isPositive());
    }

    @Test
    void currenciesAreNeverAddedTogether() {
        when(gateway.findUnsettledCaptured(merchantId))
                .thenReturn(List.of(payment(10_000, "INR"), payment(10_000, "USD"), payment(5_000, "INR")));

        executor.processForMerchant(merchantId, LocalDate.now());

        assertThat(transferredAmounts(2)).extracting(Money::getCurrency).containsExactlyInAnyOrder("INR", "USD");
    }

    @Test
    void paymentsAlreadyInAPayoutInFlightAreLeftOut() {
        PaymentSettlementView alreadyPaidOut = payment(50_000, "INR");
        PaymentSettlementView fresh = payment(10_000, "INR");
        when(gateway.findUnsettledCaptured(merchantId)).thenReturn(List.of(alreadyPaidOut, fresh));
        when(settlementPaymentRepository.findPaymentIdsInSettlements(eq(merchantId), anyCollection()))
                .thenReturn(Set.of(alreadyPaidOut.paymentId()));

        executor.processForMerchant(merchantId, LocalDate.now());

        // only the fresh 10,000 is paid: 10,000 - 200 - 36
        assertThat(transferredAmounts(1)).singleElement()
                .satisfies(m -> assertThat(m.getAmountUnits()).isEqualTo(9_764));
    }

    @Test
    void nothingIsPaidWhenEveryPaymentIsAlreadyInFlight() {
        PaymentSettlementView p = payment(50_000, "INR");
        when(gateway.findUnsettledCaptured(merchantId)).thenReturn(List.of(p));
        when(settlementPaymentRepository.findPaymentIdsInSettlements(eq(merchantId), anyCollection()))
                .thenReturn(Set.of(p.paymentId()));

        executor.processForMerchant(merchantId, LocalDate.now());

        verify(settlementRepository, never()).save(any());
        verify(bank, never()).initiate(any(), any(), any(), any(), any());
    }

    @Test
    void aMerchantWithoutABankAccountIsSkippedAndNothingIsWritten() {
        when(gateway.findUnsettledCaptured(merchantId)).thenReturn(List.of(payment(10_000, "INR")));
        when(gateway.getSettlementBankDetails(merchantId)).thenReturn(new SettlementBankDetails(null, null, null));

        executor.processForMerchant(merchantId, LocalDate.now());

        verify(settlementRepository, never()).save(any());
        verify(settlementPaymentRepository, never()).saveAll(anyList());
        verify(bank, never()).initiate(any(), any(), any(), any(), any());
    }

    @Test
    void aBlankIfscCountsAsNoBankAccount() {
        when(gateway.findUnsettledCaptured(merchantId)).thenReturn(List.of(payment(10_000, "INR")));
        when(gateway.getSettlementBankDetails(merchantId)).thenReturn(new SettlementBankDetails("123456789012", " ", "H"));

        executor.processForMerchant(merchantId, LocalDate.now());

        verify(bank, never()).initiate(any(), any(), any(), any(), any());
    }

    @Test
    void aFailedBankDetailsLookupSkipsTheMerchantWithoutWriting() {
        when(gateway.findUnsettledCaptured(merchantId)).thenReturn(List.of(payment(10_000, "INR")));
        when(gateway.getSettlementBankDetails(merchantId)).thenThrow(new RuntimeException("merchant-service down"));

        executor.processForMerchant(merchantId, LocalDate.now());

        verify(settlementRepository, never()).save(any());
    }

    @Test
    void aFailedTransferMarksOnlyThatSettlementFailed() {
        when(gateway.findUnsettledCaptured(merchantId)).thenReturn(List.of(payment(10_000, "INR")));
        when(bank.initiate(any(), any(), any(), anyString(), anyString())).thenThrow(new RuntimeException("bank down"));

        executor.processForMerchant(merchantId, LocalDate.now());

        ArgumentCaptor<Settlement> saved = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository, times(2)).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SettlementStatus.FAILED);
    }
}
