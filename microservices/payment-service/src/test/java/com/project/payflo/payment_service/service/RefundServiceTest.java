package com.project.payflo.payment_service.service;

import com.project.payflo.common_lib.exception.IdempotencyKeyReusedException;
import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.EventAggregateType;
import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.common_lib.enums.RefundStatus;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.payment_service.dto.request.CreateRefundRequest;
import com.project.payflo.payment_service.dto.response.RefundResponse;
import com.project.payflo.payment_service.entity.OrderRecord;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.entity.Refund;
import com.project.payflo.payment_service.outbox.OutboxEventPublisher;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.repository.RefundRepository;
import com.project.payflo.payment_service.statemachine.PaymentTransitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundServiceTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final RefundRepository refundRepository = mock(RefundRepository.class);
    private final PaymentTransitionService transitions = mock(PaymentTransitionService.class);
    private final OutboxEventPublisher events = mock(OutboxEventPublisher.class);
    private final RefundService service = new RefundService(paymentRepository, refundRepository, transitions, events);

    private final UUID merchantId = UUID.randomUUID();
    private Payment payment;

    @BeforeEach
    void aCapturedPaymentOfTenThousand() {
        OrderRecord order = OrderRecord.builder().id(UUID.randomUUID()).build();
        payment = Payment.builder().id(UUID.randomUUID()).order(order).merchantId(merchantId)
                .amount(Money.inr(10_000)).method(PaymentMethod.UPI).status(PaymentStatus.CAPTURED).build();
        when(paymentRepository.findByIdAndMerchantIdForUpdate(payment.getId(), merchantId)).thenReturn(Optional.of(payment));
        when(refundRepository.sumAmountUnits(eq(payment.getId()), anyCollection())).thenReturn(0L);
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        // what the real transition does to the payment
        doAnswer(inv -> {
            payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
            return PaymentStatus.PARTIALLY_REFUNDED;
        }).when(transitions).apply(payment, PaymentEvent.REFUND_INIT);
    }

    private void assertRejected(Runnable call, String errorCode) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
        verify(refundRepository, never()).save(any());
    }

    @Test
    void withNoAmountItRefundsWhatIsLeftOfThePayment() {
        RefundResponse response = service.create(merchantId, payment.getId(), null, null);

        assertThat(response.amount().getAmountUnits()).isEqualTo(10_000);
        assertThat(response.amount().getCurrency()).isEqualTo("INR");
        assertThat(response.status()).isEqualTo(RefundStatus.PENDING);
        verify(transitions).apply(payment, PaymentEvent.REFUND_INIT);
    }

    @Test
    void aPartialRefundTakesOnlyThatMuch() {
        RefundResponse response = service.create(merchantId, payment.getId(), new CreateRefundRequest(2_500, Map.of("reason", "late")), null);

        assertThat(response.amount().getAmountUnits()).isEqualTo(2_500);
        assertThat(response.notes()).containsEntry("reason", "late");
    }

    @Test
    void youCannotRefundMoreThanThePayment() {
        assertRejected(() -> service.create(merchantId, payment.getId(), new CreateRefundRequest(10_001, null), null),
                "REFUND_EXCEEDS_PAYMENT");
    }

    @Test
    void refundsAlreadyWaitingOrDoneCountAgainstWhatIsLeft() {
        when(refundRepository.sumAmountUnits(eq(payment.getId()), anyCollection())).thenReturn(6_000L);

        assertRejected(() -> service.create(merchantId, payment.getId(), new CreateRefundRequest(5_000, null), null),
                "REFUND_EXCEEDS_PAYMENT");

        // and the default is only the 4,000 that remains
        assertThat(service.create(merchantId, payment.getId(), null, null).amount().getAmountUnits()).isEqualTo(4_000);
    }

    @Test
    void aPaymentThatIsAlreadyFullyClaimedHasNothingLeft() {
        when(refundRepository.sumAmountUnits(eq(payment.getId()), anyCollection())).thenReturn(10_000L);

        assertRejected(() -> service.create(merchantId, payment.getId(), null, null), "REFUND_EXCEEDS_PAYMENT");
    }

    @Test
    void aPaymentThatHasBeenPaidOutCanNoLongerBeRefunded() {
        payment.setStatus(PaymentStatus.SETTLED);

        assertRejected(() -> service.create(merchantId, payment.getId(), null, null), "PAYMENT_ALREADY_SETTLED");
    }

    @Test
    void onlyACapturedPaymentCanBeRefunded() {
        for (PaymentStatus status : new PaymentStatus[]{PaymentStatus.AUTHORIZING, PaymentStatus.FAILED, PaymentStatus.REFUNDED}) {
            payment.setStatus(status);
            assertRejected(() -> service.create(merchantId, payment.getId(), null, null), "PAYMENT_NOT_REFUNDABLE");
        }
    }

    @Test
    void anotherMerchantsPaymentIsNotFound() {
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), payment.getId(), null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aRetryWithTheSameKeyReturnsTheOriginalRefundAndMakesNoSecondOne() {
        Refund existing = Refund.builder().id(UUID.randomUUID()).payment(payment).merchantId(merchantId)
                .amount(Money.inr(3_000)).status(RefundStatus.PENDING).idempotencyKey("key-1").build();
        when(refundRepository.findByMerchantIdAndIdempotencyKey(merchantId, "key-1")).thenReturn(Optional.of(existing));

        RefundResponse response = service.create(merchantId, payment.getId(), new CreateRefundRequest(3_000, null), "key-1");

        assertThat(response.id()).isEqualTo(existing.getId());
        verify(refundRepository, never()).save(any());
        verify(transitions, never()).apply(any(), any());
    }

    @Test
    void theKeyIsStoredWithTheRefund() {
        service.create(merchantId, payment.getId(), null, "key-2");

        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(saved.capture());
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo("key-2");
    }

    @Test
    void creatingARefundPublishesARefundEventAndAPaymentStatusChange() {
        service.create(merchantId, payment.getId(), null, null);

        verify(events).publish(eq(EventAggregateType.REFUND), any(UUID.class), eq("REFUND_CREATED"), anyMap());
        // CAPTURED became PARTIALLY_REFUNDED, which a webhook receiver needs to hear about
        verify(events).publish(eq(EventAggregateType.PAYMENT), eq(payment.getId()), eq("PAYMENT_STATUS_CHANGED"), anyMap());
    }

    @Test
    void aSecondRefundOfAPartlyRefundedPaymentDoesNotRepeatThePaymentStatusEvent() {
        payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        when(refundRepository.sumAmountUnits(eq(payment.getId()), anyCollection())).thenReturn(1_000L);

        service.create(merchantId, payment.getId(), new CreateRefundRequest(500, null), null);

        verify(events, never()).publish(eq(EventAggregateType.PAYMENT), any(), eq("PAYMENT_STATUS_CHANGED"), anyMap());
    }

    @Test
    void reusingAKeyForAnotherAmountIsRefusedNotReplayed() {
        Refund existing = Refund.builder().id(UUID.randomUUID()).payment(payment).merchantId(merchantId)
                .amount(Money.inr(3_000)).status(RefundStatus.PENDING).idempotencyKey("key-1").build();
        when(refundRepository.findByMerchantIdAndIdempotencyKey(merchantId, "key-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(merchantId, payment.getId(), new CreateRefundRequest(4_000, null), "key-1"))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        verify(refundRepository, never()).save(any());
    }

    @Test
    void reusingAKeyForAnotherPaymentIsRefusedNotReplayed() {
        Refund existing = Refund.builder().id(UUID.randomUUID()).payment(payment).merchantId(merchantId)
                .amount(Money.inr(3_000)).status(RefundStatus.PENDING).idempotencyKey("key-1").build();
        when(refundRepository.findByMerchantIdAndIdempotencyKey(merchantId, "key-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(merchantId, UUID.randomUUID(), new CreateRefundRequest(3_000, null), "key-1"))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        verify(refundRepository, never()).save(any());
    }
}
