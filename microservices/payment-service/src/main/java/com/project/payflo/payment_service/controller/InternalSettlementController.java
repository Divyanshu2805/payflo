package com.project.payflo.payment_service.controller;

import com.project.payflo.common_lib.dto.PaymentSettlementView;
import com.project.payflo.payment_service.api.PaymentLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/payments")
public class InternalSettlementController {

    // "No hold": every capture is older than this. (A null here is not an option for the query's comparison.)
    private static final LocalDateTime NO_HOLD = LocalDateTime.of(9999, 1, 1, 0, 0);

    private final PaymentLookupService settlementLookupService;

    // Paged and oldest first. capturedBefore is the settlement hold: payments captured after it aren't due yet.
    @GetMapping("/unsettled-captured")
    public List<PaymentSettlementView> findUnsettledCaptured(
            @RequestParam UUID merchantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime capturedBefore,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "1000") int size) {
        return settlementLookupService.findUnsettledCapturedPayments(
                merchantId, capturedBefore != null ? capturedBefore : NO_HOLD, page, size);
    }

    @PostMapping("/mark-settled")
    public void markSettled(@RequestBody List<UUID> paymentIds) {
        settlementLookupService.markSettled(paymentIds);
    }
}
