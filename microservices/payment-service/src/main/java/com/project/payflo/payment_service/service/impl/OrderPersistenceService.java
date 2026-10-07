package com.project.payflo.payment_service.service.impl;

import java.util.Optional;
import java.util.Objects;
import com.project.payflo.common_lib.exception.IdempotencyKeyReusedException;
import com.project.payflo.common_lib.enums.EventAggregateType;
import com.project.payflo.common_lib.enums.OrderStatus;
import com.project.payflo.common_lib.exception.DuplicateResourceException;
import com.project.payflo.payment_service.dto.request.CreateOrderRequest;
import com.project.payflo.payment_service.dto.response.OrderResponse;
import com.project.payflo.payment_service.entity.OrderRecord;
import com.project.payflo.payment_service.mapper.OrderMapper;
import com.project.payflo.payment_service.outbox.OutboxEventPublisher;
import com.project.payflo.payment_service.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderPersistenceService {

    private final OrderRepository orderRepository;
    private final OutboxEventPublisher eventPublisher;
    private final OrderMapper orderMapper;

    /**
     * The order already created under this key, if any. The same key for a different request (another amount, receipt or
     * notes) is refused rather than answered with the first order.
     */
    @Transactional(readOnly = true)
    public Optional<OrderResponse> findReplay(UUID merchantId, CreateOrderRequest request, String idempotencyKey) {
        return orderRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey).map(existing -> {
            if (!sameRequest(existing, request)) {
                throw new IdempotencyKeyReusedException(
                        "This idempotency key was already used for a different order. Use a new key for a new request.");
            }
            return orderMapper.toResponse(existing);
        });
    }

    private static boolean sameRequest(OrderRecord existing, CreateOrderRequest request) {
        return existing.getAmount().getAmountUnits() == request.amount().getAmountUnits()
                && Objects.equals(existing.getAmount().getCurrency(), request.amount().getCurrency())
                && Objects.equals(existing.getReceipt(), request.receipt())
                && Objects.equals(existing.getNotes(), request.notes());
    }

    @Transactional
    public OrderResponse persist(UUID merchantId, CreateOrderRequest request, UUID customerId,
                                  int defaultOrderExpiryMinutes, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<OrderResponse> replay = findReplay(merchantId, request, idempotencyKey);
            if (replay.isPresent()) {
                return replay.get();
            }
        }

        // Checked here, in the same short transaction as the insert, rather than before the remote
        // customer lookup; the (merchant_id, receipt) unique index still catches a concurrent duplicate.
        if (request.receipt() != null && orderRepository.existsByMerchantIdAndReceipt(merchantId, request.receipt())) {
            throw new DuplicateResourceException("ORDER_RECEIPT_DUPLICATE", "Order with receipt already exists: " + request.receipt());
        }

        OrderRecord order = OrderRecord.builder()
                .receipt(request.receipt())
                .amount(request.amount())
                .notes(request.notes())
                .merchantId(merchantId)
                .customerId(customerId)
                .idempotencyKey(idempotencyKey)
                .orderStatus(OrderStatus.CREATED)
                .expiresAt(request.expiresAt() != null ? request.expiresAt() :
                        LocalDateTime.now().plusMinutes(defaultOrderExpiryMinutes))
                .build();

        order = orderRepository.save(order);

        eventPublisher.publish(EventAggregateType.ORDER, order.getId(), "ORDER_CREATED",
                Map.of("orderId", order.getId(),
                        "merchantId", merchantId.toString(),
                        "orderStatus", order.getOrderStatus().name(),
                        "amountUnits", order.getAmount().getAmountUnits(),
                        "amountCurrency", order.getAmount().getCurrency()
                )
        );

        return orderMapper.toResponse(order);
    }
}
