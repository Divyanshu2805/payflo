package com.project.payflo.payment_service.timeout;

import com.project.payflo.common_lib.enums.EventAggregateType;
import com.project.payflo.common_lib.enums.OrderStatus;
import com.project.payflo.common_lib.enums.PaymentEvent;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.payment_service.entity.OrderRecord;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.outbox.OutboxEventPublisher;
import com.project.payflo.payment_service.repository.OrderRepository;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.saga.PaymentAuthorizationRecorder;
import com.project.payflo.payment_service.statemachine.PaymentTransitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One timeout per call, each in its own transaction under a row lock, so a payment that the bank answers
 * at the same moment is never both resolved and timed out. Every call re-checks the row after locking it:
 * the sweeper chose it from an earlier read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTimeoutService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentTransitionService paymentTransitionService;
    private final OutboxEventPublisher eventPublisher;

    /** The bank never answered: fail the payment, which lets the order be paid again. */
    @Transactional
    public boolean timeOutAuthorizing(UUID paymentId, LocalDateTime createdBefore) {
        Payment payment = lockIfStill(paymentId, PaymentStatus.AUTHORIZING, createdBefore);
        if (payment == null) return false;

        paymentTransitionService.apply(payment, PaymentEvent.AUTHORIZE_FAIL);
        payment.setErrorCode("PAYMENT_AUTHORIZATION_TIMEOUT");
        payment.setErrorDescription("The bank did not respond in time");
        payment.setFailedAt(LocalDateTime.now());
        paymentRepository.save(payment);
        publishStatusChanged(payment);
        return true;
    }

    /** Authorized but never captured within the window: the authorization lapses. */
    @Transactional
    public boolean timeOutAuthorized(UUID paymentId, LocalDateTime createdBefore) {
        Payment payment = lockIfStill(paymentId, PaymentStatus.AUTHORIZED, createdBefore);
        if (payment == null) return false;

        paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_TIMEOUT);
        payment.setErrorCode("CAPTURE_TIMEOUT");
        payment.setErrorDescription("The payment was authorized but not captured in time");
        paymentRepository.save(payment);
        publishStatusChanged(payment);
        return true;
    }

    /** An unpaid order past its expiry stops being payable. An order with a payment in flight is left alone. */
    @Transactional
    public boolean expireOrder(UUID orderId) {
        OrderRecord order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null
                || (order.getOrderStatus() != OrderStatus.CREATED && order.getOrderStatus() != OrderStatus.ATTEMPTED)
                || !order.getExpiresAt().isBefore(LocalDateTime.now())
                || paymentRepository.existsByOrder_IdAndStatusIn(orderId, PaymentAuthorizationRecorder.LIVE_PAYMENT_STATUSES)) {
            return false;
        }

        order.setOrderStatus(OrderStatus.EXPIRED);
        orderRepository.save(order);
        eventPublisher.publish(EventAggregateType.ORDER, order.getId(), "ORDER_EXPIRED",
                Map.of("orderId", order.getId(),
                        "merchantId", order.getMerchantId().toString(),
                        "orderStatus", order.getOrderStatus().name(),
                        "amountUnits", order.getAmount().getAmountUnits(),
                        "amountCurrency", order.getAmount().getCurrency()));
        return true;
    }

    private Payment lockIfStill(UUID paymentId, PaymentStatus expected, LocalDateTime createdBefore) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != expected || !payment.getCreatedAt().isBefore(createdBefore)) {
            return null;
        }
        return payment;
    }

    private void publishStatusChanged(Payment payment) {
        eventPublisher.publish(EventAggregateType.PAYMENT, payment.getId(), "PAYMENT_STATUS_CHANGED",
                Map.of("orderId", payment.getOrder().getId().toString(),
                        "paymentId", payment.getId().toString(),
                        "merchantId", payment.getMerchantId().toString(),
                        "paymentStatus", payment.getStatus().name(),
                        "amountUnits", payment.getAmount().getAmountUnits(),
                        "amountCurrency", payment.getAmount().getCurrency(),
                        "paymentMethod", payment.getMethod()));
    }
}
