package com.project.payflo.payment_service.service;


import com.project.payflo.payment_service.dto.request.CreateOrderRequest;
import com.project.payflo.payment_service.dto.response.OrderResponse;
import com.project.payflo.payment_service.dto.response.PaymentResponse;

import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.OrderStatus;

import java.util.List;
import java.util.UUID;

public interface OrderService {
    // idempotencyKey is optional: with one, a retry of the same request returns the order already created
    OrderResponse create(UUID merchantId, CreateOrderRequest request, String idempotencyKey);

    OrderResponse getById(UUID merchantId, UUID orderId);

    PageResponse<OrderResponse> list(UUID merchantId, OrderStatus status, int page, int size);

    OrderResponse cancel(UUID merchantId, UUID orderId);

    List<PaymentResponse> listPayments(UUID merchantId, UUID orderId);
}
