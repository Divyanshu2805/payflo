package com.project.payflo.payment.controller;

import com.project.payflo.merchant.security.MerchantContext;
import com.project.payflo.payment.dto.request.CreateOrderRequest;
import com.project.payflo.payment.dto.response.OrderResponse;
import com.project.payflo.payment.dto.response.PaymentResponse;
import com.project.payflo.payment.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final MerchantContext merchantContext;

    @PostMapping
    public ResponseEntity<OrderResponse> create(@RequestBody @Valid CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.create(merchantContext.getMerchantId(), request));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getById(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.getById(merchantContext.getMerchantId(), orderId));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancel(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.cancel(merchantContext.getMerchantId(), orderId));
    }

    @GetMapping("/{orderId}/payments")
    public ResponseEntity<List<PaymentResponse>> listPayments(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.listPayments(merchantContext.getMerchantId(), orderId));
    }
}
