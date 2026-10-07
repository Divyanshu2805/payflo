package com.project.payflo.payment_service.controller;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.OrderStatus;
import com.project.payflo.payment_service.dto.request.CreateOrderRequest;
import com.project.payflo.payment_service.dto.response.OrderResponse;
import com.project.payflo.payment_service.dto.response.PaymentResponse;
import com.project.payflo.payment_service.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final MerchantContext merchantContext;

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> create(@RequestBody @Valid CreateOrderRequest request,
                                                @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.create(merchantContext.getMerchantId(), request, idempotencyKey));
    }

    @GetMapping
    public ResponseEntity<PageResponse<OrderResponse>> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size) {
        return ResponseEntity.ok(orderService.list(merchantContext.getMerchantId(), status, page, size));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> get(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.getById(merchantContext.getMerchantId(), orderId));
    }

    @GetMapping("/{orderId}/payments")
    public ResponseEntity<List<PaymentResponse>> payments(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.listPayments(merchantContext.getMerchantId(), orderId));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancel(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.cancel(merchantContext.getMerchantId(), orderId));
    }
}
