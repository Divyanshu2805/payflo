package com.project.payflo.merchant_service.controller;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.merchant_service.dto.request.MerchantProfileRequests.SettlementBankRequest;
import com.project.payflo.merchant_service.dto.request.MerchantProfileRequests.UpdateProfileRequest;
import com.project.payflo.merchant_service.dto.response.MerchantProfileResponse;
import com.project.payflo.merchant_service.service.MerchantProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/merchants/me")
@RequiredArgsConstructor
public class MerchantProfileController {

    private final MerchantProfileService merchantProfileService;
    private final MerchantContext merchantContext;

    @GetMapping
    public ResponseEntity<MerchantProfileResponse> get() {
        return ResponseEntity.ok(merchantProfileService.get(merchantContext.getMerchantId()));
    }

    @PutMapping
    public ResponseEntity<MerchantProfileResponse> update(@Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(merchantProfileService.update(merchantContext.getMerchantId(), request));
    }

    @PutMapping("/settlement-bank")
    public ResponseEntity<MerchantProfileResponse> updateSettlementBank(@Valid @RequestBody SettlementBankRequest request) {
        return ResponseEntity.ok(merchantProfileService.updateSettlementBank(merchantContext.getMerchantId(), request));
    }

    @PostMapping("/kyc")
    public ResponseEntity<MerchantProfileResponse> submitKyc() {
        return ResponseEntity.ok(merchantProfileService.submitKyc(merchantContext.getMerchantId()));
    }
}
