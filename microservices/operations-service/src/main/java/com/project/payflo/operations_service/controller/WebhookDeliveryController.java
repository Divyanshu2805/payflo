package com.project.payflo.operations_service.controller;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.dto.PageResponse;
import com.project.payflo.common_lib.enums.WebhookEventStatus;
import com.project.payflo.operations_service.dto.WebhookDeliveryResponse;
import com.project.payflo.operations_service.service.WebhookDeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/webhook-deliveries")
@RequiredArgsConstructor
public class WebhookDeliveryController {

    private final WebhookDeliveryService webhookDeliveryService;
    private final MerchantContext merchantContext;

    @GetMapping
    public ResponseEntity<PageResponse<WebhookDeliveryResponse>> list(
            @RequestParam(required = false) WebhookEventStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size) {
        return ResponseEntity.ok(webhookDeliveryService.list(merchantContext.getMerchantId(), status, page, size));
    }

    @GetMapping("/{deliveryId}")
    public ResponseEntity<WebhookDeliveryResponse> get(@PathVariable UUID deliveryId) {
        return ResponseEntity.ok(webhookDeliveryService.get(merchantContext.getMerchantId(), deliveryId));
    }

    @PostMapping("/{deliveryId}/replay")
    public ResponseEntity<WebhookDeliveryResponse> replay(@PathVariable UUID deliveryId) {
        return ResponseEntity.ok(webhookDeliveryService.replay(merchantContext.getMerchantId(), deliveryId));
    }
}
