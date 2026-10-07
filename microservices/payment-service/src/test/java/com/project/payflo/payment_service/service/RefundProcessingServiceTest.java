package com.project.payflo.payment_service.service;

import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.common_lib.enums.RefundStatus;
import com.project.payflo.payment_service.entity.OrderRecord;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.entity.Refund;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.repository.RefundRepository;
import com.project.payflo.payment_service.statemachine.PaymentTransitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundProcessingServiceTest {

    private final RefundRepository refundRepository = mock(RefundRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentTransitionService transitions = mock(PaymentTransitionService.class);
    private final RefundService refundService = mock(RefundService.class);

    private Payment payment;
    private Refund refund;
    private final LocalDateTime cutoff = LocalDateTime.now().minusSeconds(3);

    // success rate 100 approves every refund, 0 declines every one: the outcome is derived from the id
    private RefundProcessingService serviceWithSuccessRate(int rate) {
        return new RefundProcessingService(refundRepository, paymentRepository, transitions, refundService, rate);
    }

    @BeforeEach
    void aPendingRefundOfAPartlyRefundedPayment() {
        OrderRecord order = OrderRecord.builder().id(UUID.randomUUID()).build();
        payment = Payment.builder().id(UUID.randomUUID()).order(order).merchantId(UUID.randomUUID())
                .amount(Money.inr(10_000)).method(PaymentMethod.CARD).status(PaymentStatus.PARTIALLY_REFUNDED).build();
        refund = Refund.builder().id(UUID.randomUUID()).payment(payment).merchantId(payment.getMerchantId())
                .amount(Money.inr(4_000)).status(RefundStatus.PENDING).build();
        refund.setCreatedAt(LocalDateTime.now().minusMinutes(1));

        when(refundRepository.findById(refund.getId())).thenReturn(Optional.of(refund));
        when(refundRepository.findByIdForUpdate(refund.getId())).thenReturn(Optional.of(refund));
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(refundRepository.saveAndFlush(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void processedSoFar(long units) {
        when(refundRepository.sumAmountUnits(eq(payment.getId()), eq(List.of(RefundStatus.PROCESSED)))).thenReturn(units);
    }

    private void stillActive(long units) {
        when(refundRepository.sumAmountUnits(eq(payment.getId()), eq(RefundService.ACTIVE))).thenReturn(units);
    }

    private void transitionsChangeTheStatusTo(PaymentEvent event, PaymentStatus to) {
        doAnswer(inv -> {
            payment.setStatus(to);
            return to;
        }).when(transitions).apply(payment, event);
    }

    @Test
    void anApprovedPartialRefundIsProcessedAndLeavesThePaymentPartlyRefunded() {
        processedSoFar(4_000);

        assertThat(serviceWithSuccessRate(100).resolve(refund.getId(), cutoff)).isTrue();

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PROCESSED);
        assertThat(refund.getBankReference()).isNotBlank();
        assertThat(refund.getProcessedAt()).isNotNull();
        verify(transitions, never()).apply(any(), any());
        verify(refundService).publishRefundEvent(refund, "REFUND_PROCESSED");
    }

    @Test
    void whenTheRefundsAddUpToThePaymentItBecomesRefunded() {
        processedSoFar(10_000);
        transitionsChangeTheStatusTo(PaymentEvent.REFUND_COMPLETE, PaymentStatus.REFUNDED);

        serviceWithSuccessRate(100).resolve(refund.getId(), cutoff);

        verify(transitions).apply(payment, PaymentEvent.REFUND_COMPLETE);
        assertThat(payment.getRefundedAt()).isNotNull();
        verify(refundService).publishPaymentStatusChanged(payment); // PARTIALLY_REFUNDED -> REFUNDED
    }

    @Test
    void aDeclinedRefundWithNothingElseRefundedPutsThePaymentBackToCaptured() {
        stillActive(0);
        transitionsChangeTheStatusTo(PaymentEvent.REFUND_FAIL, PaymentStatus.CAPTURED);

        serviceWithSuccessRate(0).resolve(refund.getId(), cutoff);

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.getErrorCode()).isEqualTo("SIM_REFUND_DECLINED");
        verify(transitions).apply(payment, PaymentEvent.REFUND_FAIL);
        verify(refundService).publishRefundEvent(refund, "REFUND_FAILED");
        verify(refundService).publishPaymentStatusChanged(payment);
    }

    @Test
    void aDeclinedRefundLeavesThePaymentPartlyRefundedWhileOthersStand() {
        stillActive(2_000);

        serviceWithSuccessRate(0).resolve(refund.getId(), cutoff);

        verify(transitions, never()).apply(any(), any());
        verify(refundService, never()).publishPaymentStatusChanged(any());
    }

    @Test
    void aRefundThatIsNoLongerPendingIsLeftAlone() {
        refund.setStatus(RefundStatus.PROCESSED);

        assertThat(serviceWithSuccessRate(100).resolve(refund.getId(), cutoff)).isFalse();

        verify(refundRepository, never()).saveAndFlush(any());
    }

    @Test
    void aRefundTooNewToHaveBeenAnsweredIsLeftAlone() {
        refund.setCreatedAt(LocalDateTime.now());

        assertThat(serviceWithSuccessRate(100).resolve(refund.getId(), cutoff)).isFalse();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void anUnknownRefundIsIgnored() {
        assertThat(serviceWithSuccessRate(100).resolve(UUID.randomUUID(), cutoff)).isFalse();
    }
}
