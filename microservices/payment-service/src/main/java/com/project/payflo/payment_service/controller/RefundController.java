package com.project.payflo.payment_service.controller;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.RefundStatus;
import com.project.payflo.payment_service.dto.request.CreateRefundRequest;
import com.project.payflo.payment_service.dto.response.RefundResponse;
import com.project.payflo.payment_service.service.RefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;
    private final MerchantContext merchantContext;

    @PostMapping("/v1/payments/{paymentId}/refunds")
    public ResponseEntity<RefundResponse> create(@PathVariable UUID paymentId,
                                                 @Valid @RequestBody(required = false) CreateRefundRequest request,
                                                 @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(refundService.create(merchantContext.getMerchantId(), paymentId, request, idempotencyKey));
    }

    @GetMapping("/v1/payments/{paymentId}/refunds")
    public ResponseEntity<List<RefundResponse>> forPayment(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(refundService.listForPayment(merchantContext.getMerchantId(), paymentId));
    }

    @GetMapping("/v1/refunds")
    public ResponseEntity<PageResponse<RefundResponse>> list(
            @RequestParam(required = false) RefundStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size) {
        return ResponseEntity.ok(refundService.list(merchantContext.getMerchantId(), status, page, size));
    }

    @GetMapping("/v1/refunds/{refundId}")
    public ResponseEntity<RefundResponse> get(@PathVariable UUID refundId) {
        return ResponseEntity.ok(refundService.get(merchantContext.getMerchantId(), refundId));
    }
}
