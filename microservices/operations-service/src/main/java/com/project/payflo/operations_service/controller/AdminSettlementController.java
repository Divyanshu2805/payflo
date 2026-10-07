package com.project.payflo.operations_service.controller;

import com.project.payflo.operations_service.dto.AdminSettlementRunResponse;
import com.project.payflo.operations_service.service.AdminSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The platform operator's settlement API. Only the gateway lets a request in (it checks the admin key), and
 * {@code PlatformAdminFilter} refuses one without the header the gateway then sets.
 */
@RestController
@RequestMapping("/v1/admin/settlements")
@RequiredArgsConstructor
public class AdminSettlementController {

    public record RunRequest(UUID merchantId) {}

    private final AdminSettlementService adminSettlementService;

    // Optional body: {"merchantId": "..."} settles that one merchant, nothing settles every active merchant.
    @PostMapping("/run")
    public ResponseEntity<AdminSettlementRunResponse> run(@RequestBody(required = false) RunRequest request) {
        return ResponseEntity.ok(adminSettlementService.run(request != null ? request.merchantId() : null));
    }
}
