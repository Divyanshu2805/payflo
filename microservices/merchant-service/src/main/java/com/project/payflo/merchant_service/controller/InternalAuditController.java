package com.project.payflo.merchant_service.controller;

import com.project.payflo.common_lib.dto.AuditEntryRequest;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.merchant_service.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets another service put an entry in the audit log, which merchant-service owns. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/audit")
public class InternalAuditController {

    private final AuditLogService auditLogService;

    @PostMapping
    public ResponseEntity<Void> record(@RequestBody AuditEntryRequest entry) {
        if (entry == null || entry.action() == null) {
            throw new BusinessRuleViolationException("INVALID_AUDIT_ENTRY", "An audit entry needs an action");
        }
        auditLogService.record(entry);
        return ResponseEntity.noContent().build();
    }
}
