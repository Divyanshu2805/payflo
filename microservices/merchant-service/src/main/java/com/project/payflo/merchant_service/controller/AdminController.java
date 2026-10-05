package com.project.payflo.merchant_service.controller;

import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.MerchantStatus;
import com.project.payflo.merchant_service.dto.request.AdminRequests.ReactivateRequest;
import com.project.payflo.merchant_service.dto.request.AdminRequests.SuspendRequest;
import com.project.payflo.merchant_service.dto.response.AdminMerchantResponse;
import com.project.payflo.merchant_service.dto.response.AuditLogResponse;
import com.project.payflo.merchant_service.service.AdminMerchantService;
import com.project.payflo.merchant_service.service.AuditLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The platform operator's API. It acts on merchants rather than as one, so nothing here reads {@code MerchantContext}.
 * The gateway lets a request in only with the admin key and then sets the header {@code PlatformAdminFilter} requires.
 */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminMerchantService adminMerchantService;
    private final AuditLogService auditLogService;

    @GetMapping("/merchants")
    public ResponseEntity<PageResponse<AdminMerchantResponse>> listMerchants(
            @RequestParam(required = false) MerchantStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size) {
        return ResponseEntity.ok(adminMerchantService.list(status, page, size));
    }

    @GetMapping("/merchants/{merchantId}")
    public ResponseEntity<AdminMerchantResponse> getMerchant(@PathVariable UUID merchantId) {
        return ResponseEntity.ok(adminMerchantService.get(merchantId));
    }

    @PostMapping("/merchants/{merchantId}/suspend")
    public ResponseEntity<AdminMerchantResponse> suspend(@PathVariable UUID merchantId,
                                                         @Valid @RequestBody SuspendRequest request) {
        return ResponseEntity.ok(adminMerchantService.suspend(merchantId, request.reason()));
    }

    @PostMapping("/merchants/{merchantId}/reactivate")
    public ResponseEntity<AdminMerchantResponse> reactivate(@PathVariable UUID merchantId,
                                                            @Valid @RequestBody(required = false) ReactivateRequest request) {
        return ResponseEntity.ok(adminMerchantService.reactivate(merchantId, request != null ? request.reason() : null));
    }

    @GetMapping("/audit-log")
    public ResponseEntity<PageResponse<AuditLogResponse>> auditLog(
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size) {
        return ResponseEntity.ok(auditLogService.list(merchantId, action, page, size));
    }
}
