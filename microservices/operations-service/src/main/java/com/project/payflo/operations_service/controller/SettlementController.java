package com.project.payflo.operations_service.controller;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.SettlementStatus;
import com.project.payflo.operations_service.dto.SettlementResponse;
import com.project.payflo.operations_service.service.SettlementQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementQueryService settlementQueryService;
    private final MerchantContext merchantContext;

    @GetMapping
    public ResponseEntity<PageResponse<SettlementResponse>> list(
            @RequestParam(required = false) SettlementStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size) {
        return ResponseEntity.ok(settlementQueryService.list(merchantContext.getMerchantId(), status, page, size));
    }

    @GetMapping("/{settlementId}")
    public ResponseEntity<SettlementResponse> get(@PathVariable UUID settlementId) {
        return ResponseEntity.ok(settlementQueryService.get(merchantContext.getMerchantId(), settlementId));
    }

    @GetMapping("/{settlementId}/payments")
    public ResponseEntity<List<UUID>> payments(@PathVariable UUID settlementId) {
        return ResponseEntity.ok(settlementQueryService.paymentIds(merchantContext.getMerchantId(), settlementId));
    }
}
