package com.project.payflo.payment_service.service.impl;

import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import com.project.payflo.common_lib.dto.FindOrCreateCustomerRequest;
import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.EventAggregateType;
import com.project.payflo.common_lib.enums.OrderStatus;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.payment_service.client.CustomerServiceClient;
import com.project.payflo.payment_service.dto.request.CreateOrderRequest;
import com.project.payflo.payment_service.dto.response.OrderResponse;
import com.project.payflo.payment_service.dto.response.PaymentResponse;
import com.project.payflo.payment_service.entity.OrderRecord;
import com.project.payflo.payment_service.entity.Payment;
import com.project.payflo.payment_service.mapper.OrderMapper;
import com.project.payflo.payment_service.mapper.PaymentMapper;
import com.project.payflo.payment_service.outbox.OutboxEventPublisher;
import com.project.payflo.payment_service.repository.OrderRepository;
import com.project.payflo.payment_service.repository.PaymentRepository;
import com.project.payflo.payment_service.service.OrderService;
import com.project.payflo.payment_service.saga.PaymentAuthorizationRecorder;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final OrderMapper orderMapper;
    private final CustomerServiceClient customerServiceClient;
    private final OutboxEventPublisher eventPublisher;
    private final OrderPersistenceService orderPersistenceService;

    // The width of order_record.idempotency_key.
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    @Value("${payment.order.default-order-expiry-minutes:15}")
    private int defaultOrderExpiryMinutes;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @CircuitBreaker(name = "merchant-service")
    @Retry(name = "merchant-service")
    public OrderResponse create(UUID merchantId, CreateOrderRequest request, String idempotencyKey) {
        if (idempotencyKey != null && idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessRuleViolationException("IDEMPOTENCY_KEY_TOO_LONG",
                    "The idempotency key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }

        // No database access here: under NOT_SUPPORTED, a query would bind a connection to this whole
        // method and hold it across the Feign call below. The receipt check runs in persist()'s
        // transaction instead.
        UUID customerId = null;
        if (request.customer() != null) {
            customerId = customerServiceClient.findOrCreate(
                    new FindOrCreateCustomerRequest(merchantId,
                            request.customer().email(),
                            request.customer().name(),
                            request.customer().phone())
            );
        }

        try {
            return orderPersistenceService.persist(merchantId, request, customerId, defaultOrderExpiryMinutes, idempotencyKey);
        } catch (DataIntegrityViolationException raced) {
            // Two requests with the same key at the same moment: the unique index let one in. The other answers with
            // that order, as a retry would, rather than an error. (Anything else is what it always was.)
            if (idempotencyKey != null) {
                Optional<OrderResponse> winner = orderPersistenceService.findReplay(merchantId, request, idempotencyKey);
                if (winner.isPresent()) {
                    return winner.get();
                }
            }
            throw raced;
        }
    }

    @Override
    public OrderResponse getById(UUID merchantId, UUID orderId) {
        OrderRecord order = orderRepository.findByIdAndMerchantId(orderId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        return orderMapper.toResponse(order);
    }

    @Override
    public PageResponse<OrderResponse> list(UUID merchantId, OrderStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(PageResponse.clampPage(page), PageResponse.clampSize(size));
        Slice<OrderRecord> orders = status == null
                ? orderRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
                : orderRepository.findByMerchantIdAndOrderStatusOrderByCreatedAtDesc(merchantId, status, pageable);
        return PageResponse.of(orders, orderMapper::toResponse);
    }

    @Override
    @Transactional
    public OrderResponse cancel(UUID merchantId, UUID orderId) {
        // Locked, so a payment being started at this moment either sees the cancellation or is seen by it.
        OrderRecord order = orderRepository.findByIdAndMerchantIdForUpdate(orderId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        // Only an unpaid order can be cancelled. Cancelling one with a payment in flight would leave a
        // charge the merchant thinks was called off.
        if (order.getOrderStatus() != OrderStatus.CREATED && order.getOrderStatus() != OrderStatus.ATTEMPTED) {
            throw new BusinessRuleViolationException("ORDER_CANNOT_CANCEL",
                    "Cannot cancel order with status: " + order.getOrderStatus().name());
        }
        if (paymentRepository.existsByOrder_IdAndStatusIn(orderId, PaymentAuthorizationRecorder.LIVE_PAYMENT_STATUSES)) {
            throw new BusinessRuleViolationException("ORDER_CANNOT_CANCEL",
                    "Cannot cancel an order that has a payment in progress or completed");
        }

        order.setOrderStatus(OrderStatus.CANCELLED);
        order = orderRepository.save(order);

        eventPublisher.publish(EventAggregateType.ORDER, order.getId(), "ORDER_CANCELLED",
                Map.of("orderId", order.getId(),
                        "merchantId", merchantId.toString(),
                        "orderStatus", order.getOrderStatus().name(),
                        "amountUnits", order.getAmount().getAmountUnits(),
                        "amountCurrency", order.getAmount().getCurrency()
                )
        );

        return orderMapper.toResponse(order);
    }

    @Override
    public List<PaymentResponse> listPayments(UUID merchantId, UUID orderId) {
        OrderRecord order = orderRepository.findByIdAndMerchantId(orderId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        List<Payment> paymentList = paymentRepository.findByOrder_IdAndMerchantIdOrderByCreatedAtAsc(order.getId(), merchantId);

        return paymentMapper.toResponseList(paymentList);
    }
}
