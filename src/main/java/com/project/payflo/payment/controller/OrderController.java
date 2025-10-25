package com.project.payflo.payment.controller;

import com.project.payflo.payment.dto.request.CreateOrderRequest;
import com.project.payflo.payment.dto.response.OrderResponse;
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

import java.util.UUID;

@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    UUID merchantId = UUID.fromString("e313ee5e-49a8-4e9b-8d66-cb4556c642a4");

    @PostMapping
    public ResponseEntity<OrderResponse> create(@RequestBody @Valid CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.create(merchantId, request));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getById(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.getById(merchantId, orderId));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancel(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.cancel(merchantId, orderId));
    }
}
