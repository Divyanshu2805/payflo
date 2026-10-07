package com.project.payflo.payment_service.service;

import com.project.payflo.common_lib.dto.PaymentSettlementView;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.repository.RefundRepository;
import com.project.payflo.payment_service.service.impl.PaymentLookupServiceImpl;
import com.project.payflo.payment_service.statemachine.PaymentTransitionService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.SliceImpl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentLookupServiceImplTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final RefundRepository refundRepository = mock(RefundRepository.class);
    private final PaymentTransitionService transitions = mock(PaymentTransitionService.class);
    private final PaymentLookupServiceImpl service =
            new PaymentLookupServiceImpl(paymentRepository, refundRepository, transitions);

    private final UUID merchantId = UUID.randomUUID();

    private static Payment payment(PaymentStatus status, int units) {
        return Payment.builder().id(UUID.randomUUID()).amount(Money.inr(units)).method(PaymentMethod.UPI)
                .status(status).build();
    }

    // ---- what settlement is offered

    @Test
    void eachPaymentComesWithWhatHasBeenRefundedOfIt() {
        Payment refundedPart = payment(PaymentStatus.PARTIALLY_REFUNDED, 10_000);
        Payment untouched = payment(PaymentStatus.CAPTURED, 5_000);
        when(paymentRepository.findSettleable(eq(merchantId), anyCollection(), any(), anyCollection(), any()))
                .thenReturn(new SliceImpl<>(List.of(refundedPart, untouched)));
        List<Object[]> sums = new ArrayList<>();
        sums.add(new Object[]{refundedPart.getId(), 3_000L});
        when(refundRepository.sumProcessedByPayment(anyCollection())).thenReturn(sums);

        List<PaymentSettlementView> views = service.findUnsettledCapturedPayments(merchantId, LocalDateTime.now(), 0, 100);

        assertThat(views).hasSize(2);
        assertThat(views.get(0).refundedAmountUnits()).isEqualTo(3_000);
        assertThat(views.get(0).amountUnits()).isEqualTo(10_000);
        assertThat(views.get(1).refundedAmountUnits()).isZero();
    }

    @Test
    void anEmptyPageDoesNotQueryRefundsAtAll() {
        when(paymentRepository.findSettleable(eq(merchantId), anyCollection(), any(), anyCollection(), any()))
                .thenReturn(new SliceImpl<>(List.of()));

        assertThat(service.findUnsettledCapturedPayments(merchantId, LocalDateTime.now(), 0, 100)).isEmpty();

        verify(refundRepository, never()).sumProcessedByPayment(anyCollection());
    }

    // ---- marking paid out

    @Test
    void capturedAndPartlyRefundedPaymentsAreSettledThroughTheStateMachine() {
        Payment captured = payment(PaymentStatus.CAPTURED, 1_000);
        Payment partly = payment(PaymentStatus.PARTIALLY_REFUNDED, 1_000);
        when(paymentRepository.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(captured, partly));

        service.markSettled(List.of(captured.getId(), partly.getId()));

        verify(transitions).apply(captured, PaymentEvent.SETTLE);
        verify(transitions).apply(partly, PaymentEvent.SETTLE);
        assertThat(captured.getSettledAt()).isNotNull();
        assertThat(partly.getSettledAt()).isNotNull();
    }

    @Test
    void anythingElseIsSkippedSoTheCallCanBeRepeated() {
        Payment alreadySettled = payment(PaymentStatus.SETTLED, 1_000);
        Payment failed = payment(PaymentStatus.FAILED, 1_000);
        when(paymentRepository.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(alreadySettled, failed));

        service.markSettled(List.of(alreadySettled.getId(), failed.getId()));

        verify(transitions, never()).apply(any(), any());
        assertThat(alreadySettled.getSettledAt()).isNull();
    }
}
