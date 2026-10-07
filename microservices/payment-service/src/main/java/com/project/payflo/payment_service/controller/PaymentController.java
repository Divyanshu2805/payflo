package com.project.payflo.payment_service.controller;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.PaymentStatus;
import com.project.payflo.payment_service.dto.request.PaymentInitRequest;
import com.project.payflo.payment_service.dto.response.PaymentResponse;
import com.project.payflo.payment_service.service.PaymentQueryService;
import com.project.payflo.payment_service.service.PaymentService;
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
    private final PaymentQueryService paymentQueryService;
    private final MerchantContext merchantContext;

    @PostMapping
    public ResponseEntity<PaymentResponse> initiate(@Valid @RequestBody PaymentInitRequest request,
                                                    @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.initiate(merchantContext.getMerchantId(), request, idempotencyKey));
    }

    @GetMapping
    public ResponseEntity<PageResponse<PaymentResponse>> list(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size) {
        return ResponseEntity.ok(paymentQueryService.list(merchantContext.getMerchantId(), status, orderId, page, size));
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> get(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(paymentQueryService.getById(merchantContext.getMerchantId(), paymentId));
    }

    @PostMapping("/{paymentId}/capture")
    public ResponseEntity<PaymentResponse> capture(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(paymentService.capture(merchantContext.getMerchantId(), paymentId));
    }

}
