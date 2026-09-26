package com.project.payflo.payment_service.saga;

import com.project.payflo.common_lib.entity.Money;
import com.project.payflo.common_lib.enums.OrderStatus;
import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.common_lib.enums.PaymentMethod;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.payment_service.dto.request.PaymentInitRequest;
import com.project.payflo.payment_service.entity.OrderRecord;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.mapper.PaymentMapper;
import com.project.payflo.payment_service.outbox.OutboxEventPublisher;
import com.project.payflo.payment_service.repository.OrderRepository;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.statemachine.PaymentTransitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentAuthorizationRecorderTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentTransitionService transitions = mock(PaymentTransitionService.class);

    private final PaymentAuthorizationRecorder recorder = new PaymentAuthorizationRecorder(
            orderRepository, paymentRepository, transitions, mock(OutboxEventPublisher.class), mock(PaymentMapper.class));

    private final UUID merchantId = UUID.randomUUID();
    private OrderRecord order;
    private final PaymentInitRequest request =
            new PaymentInitRequest(UUID.randomUUID(), PaymentMethod.UPI, Map.of("vpa", "a@b"));

    @BeforeEach
    void anOrderThatIsPayable() {
        order = OrderRecord.builder()
                .id(request.orderId()).merchantId(merchantId).amount(Money.inr(1000))
                .orderStatus(OrderStatus.CREATED).attempts(0).expiresAt(LocalDateTime.now().plusMinutes(30))
                .build();
        when(orderRepository.findByIdAndMerchantIdForUpdate(request.orderId(), merchantId))
                .thenReturn(Optional.of(order));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void recordsThePaymentWhenNoOtherPaymentIsLive() {
        when(paymentRepository.existsByOrder_IdAndStatusIn(any(), anyCollection())).thenReturn(false);

        Payment payment = recorder.recordPayment(merchantId, request, "key-1");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.ATTEMPTED);
        assertThat(order.getAttempts()).isEqualTo(1);
        verify(transitions).apply(payment, PaymentEvent.AUTHORIZE_ATTEMPT);
    }

    @Test
    void refusesASecondPaymentWhileAnotherIsLive() {
        when(paymentRepository.existsByOrder_IdAndStatusIn(any(), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> recorder.recordPayment(merchantId, request, null))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ORDER_PAYMENT_IN_PROGRESS"));

        verify(paymentRepository, never()).save(any());
        assertThat(order.getAttempts()).isZero();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyPaymentsThatCouldHoldMoneyCountAsLive() {
        when(paymentRepository.existsByOrder_IdAndStatusIn(any(), anyCollection())).thenReturn(false);
        recorder.recordPayment(merchantId, request, "key-1");

        ArgumentCaptor<Collection<PaymentStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(paymentRepository).existsByOrder_IdAndStatusIn(any(), statuses.capture());

        assertThat(statuses.getValue()).contains(
                PaymentStatus.AUTHORIZING, PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURING, PaymentStatus.CAPTURED);
        // A failed attempt must not block a retry.
        assertThat(statuses.getValue()).doesNotContain(
                PaymentStatus.FAILED, PaymentStatus.CANCELLED, PaymentStatus.AUTH_EXPIRED);
    }

    @Test
    void anOrderThatIsAlreadyPaidIsStillNotPayable() {
        order.setOrderStatus(OrderStatus.PAID);

        assertThatThrownBy(() -> recorder.recordPayment(merchantId, request, null))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ORDER_NOT_PAYABLE"));
    }
}
