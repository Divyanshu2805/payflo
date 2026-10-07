package com.project.payflo.operations_service.client;

import com.project.payflo.common_lib.dto.AuditEntryRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** merchant-service owns the audit log; this puts an entry in it (see {@code InternalAuditController}). */
@FeignClient(name = "merchant-service", contextId = "merchant-audit", path = "/internal/audit",
        url = "${MERCHANT_SERVICE_URI:}")
public interface AuditServiceClient {

    @PostMapping
    void record(@RequestBody AuditEntryRequest entry);
}
