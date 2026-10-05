package com.project.payflo.merchant_service.controller;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.UserRole;
import com.project.payflo.merchant_service.dto.response.AuditLogResponse;
import com.project.payflo.merchant_service.security.CallerPolicy;
import com.project.payflo.merchant_service.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A merchant's own audit log: what was done to its account, by whom, newest first. */
@RestController
@RequestMapping("/v1/merchants/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final CallerPolicy callerPolicy;
    private final MerchantContext merchantContext;

    // A dashboard owner or admin only: it names the team's emails and what each did, which an API key (a merchant's
    // backend, possibly a leaked one) and a read-only team member have no need to see.
    @GetMapping
    public ResponseEntity<PageResponse<AuditLogResponse>> list(
            @RequestParam(required = false) AuditAction action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size) {
        callerPolicy.requireRole(UserRole.OWNER, UserRole.ADMIN);
        return ResponseEntity.ok(auditLogService.list(merchantContext.getMerchantId(), action, page, size));
    }
}
