package com.project.payflo.merchant_service.controller;

import com.project.payflo.common_lib.dto.SettlementBankDetails;
import com.project.payflo.common_lib.dto.WebhookTarget;
import com.project.payflo.common_lib.enums.MerchantStatus;
import com.project.payflo.merchant_service.api.MerchantLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/merchants")
public class InternalMerchantController {

    private final MerchantLookupService merchantLookupService;

    @GetMapping("/{merchantId}/webhook-targets")
    public List<WebhookTarget> getActiveConfigsForEvent(@PathVariable UUID merchantId,
                                                        @RequestParam String eventType) {
        return merchantLookupService.getActiveConfigsForEvent(merchantId, eventType);
    }

    // The secret as it is now: a delivery is signed when it is sent, so a rotation applies to retries too.
    @GetMapping("/{merchantId}/webhook-targets/{configId}")
    public WebhookTarget getWebhookTarget(@PathVariable UUID merchantId, @PathVariable UUID configId) {
        return merchantLookupService.getWebhookTarget(merchantId, configId);
    }

    @GetMapping("/active-ids")
    public List<UUID> listActiveMerchantIds() {
        return merchantLookupService.listActiveMerchantIds();
    }

    @GetMapping("/{merchantId}/status")
    public MerchantStatus getStatus(@PathVariable UUID merchantId) {
        return merchantLookupService.getStatus(merchantId);
    }

    @GetMapping("/{merchantId}/settlement-bank-details")
    public SettlementBankDetails getSettlementBankDetails(@PathVariable UUID merchantId) {
        return merchantLookupService.getSettlementBankDetails(merchantId);
    }
}
