package com.project.payflo.payment.controller;

import com.project.payflo.payment.dto.request.PaymentInitRequest;
import com.project.payflo.payment.dto.response.PaymentResponse;
import com.project.payflo.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RequestMapping("/v1/payments")
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private UUID merchantId = UUID.fromString("e313ee5e-49a8-4e9b-8d66-cb4556c642a4");

    @PostMapping
    public ResponseEntity<PaymentResponse> initiate(@Valid @RequestBody PaymentInitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.initiate(merchantId, request));
    }

}
