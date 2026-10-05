package com.project.payflo.payment_service.timeout;

import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.OrderStatus;
import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.payment_service.entity.OrderRecord;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.outbox.OutboxEventPublisher;
import com.project.payflo.payment_service.repository.OrderRepository;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.statemachine.PaymentTransitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentTimeoutServiceTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentTransitionService transitions = mock(PaymentTransitionService.class);
    private final OutboxEventPublisher events = mock(OutboxEventPublisher.class);
    private final PaymentTimeoutService service =
            new PaymentTimeoutService(paymentRepository, orderRepository, transitions, events);

    private final LocalDateTime cutoff = LocalDateTime.now().minusMinutes(15);
    private OrderRecord order;
    private Payment payment;

    @BeforeEach
    void anOldAuthorizingPayment() {
        order = OrderRecord.builder().id(UUID.randomUUID()).merchantId(UUID.randomUUID()).amount(Money.inr(1000))
                .orderStatus(OrderStatus.ATTEMPTED).expiresAt(LocalDateTime.now().minusMinutes(1)).build();
        payment = Payment.builder().id(UUID.randomUUID()).order(order).merchantId(order.getMerchantId())
                .amount(Money.inr(1000)).method(PaymentMethod.UPI).status(PaymentStatus.AUTHORIZING).build();
        payment.setCreatedAt(LocalDateTime.now().minusHours(1));
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    }

    @Test
    void anUnansweredAuthorizationIsFailedWithATimeoutCodeAndAnEventIsPublished() {
        assertThat(service.timeOutAuthorizing(payment.getId(), cutoff)).isTrue();

        verify(transitions).apply(payment, PaymentEvent.AUTHORIZE_FAIL);
        assertThat(payment.getErrorCode()).isEqualTo("PAYMENT_AUTHORIZATION_TIMEOUT");
        verify(events).publish(any(), eq(payment.getId()), eq("PAYMENT_STATUS_CHANGED"), anyMap());
    }

    @Test
    void aPaymentTheBankAnsweredInTheMeantimeIsLeftAlone() {
        payment.setStatus(PaymentStatus.CAPTURED);

        assertThat(service.timeOutAuthorizing(payment.getId(), cutoff)).isFalse();

        verify(transitions, never()).apply(any(), any());
        verify(events, never()).publish(any(), any(), any(), anyMap());
    }

    @Test
    void aPaymentThatIsNotYetOldEnoughIsLeftAlone() {
        payment.setCreatedAt(LocalDateTime.now().minusMinutes(2));

        assertThat(service.timeOutAuthorizing(payment.getId(), cutoff)).isFalse();
        verify(transitions, never()).apply(any(), any());
    }

    @Test
    void anAuthorizationThatWasNeverCapturedLapses() {
        payment.setStatus(PaymentStatus.AUTHORIZED);

        assertThat(service.timeOutAuthorized(payment.getId(), cutoff)).isTrue();

        verify(transitions).apply(payment, PaymentEvent.CAPTURE_TIMEOUT);
        assertThat(payment.getErrorCode()).isEqualTo("CAPTURE_TIMEOUT");
    }

    @Test
    void anUnpaidOrderPastItsExpiryIsExpiredAndAnnounced() {
        when(paymentRepository.existsByOrder_IdAndStatusIn(eq(order.getId()), anyCollection())).thenReturn(false);

        assertThat(service.expireOrder(order.getId())).isTrue();

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
        verify(events).publish(any(), eq(order.getId()), eq("ORDER_EXPIRED"), anyMap());
    }

    @Test
    void anOrderWithAPaymentStillInFlightIsNotExpired() {
        when(paymentRepository.existsByOrder_IdAndStatusIn(eq(order.getId()), anyCollection())).thenReturn(true);

        assertThat(service.expireOrder(order.getId())).isFalse();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.ATTEMPTED);
    }

    @Test
    void aPaidOrderIsNeverExpired() {
        order.setOrderStatus(OrderStatus.PAID);

        assertThat(service.expireOrder(order.getId())).isFalse();
        verify(events, never()).publish(any(), any(), any(), anyMap());
    }

    @Test
    void anOrderThatHasNotYetExpiredIsLeftAlone() {
        order.setExpiresAt(LocalDateTime.now().plusMinutes(10));

        assertThat(service.expireOrder(order.getId())).isFalse();
    }
}
