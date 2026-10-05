package com.project.payflo.api_gateway_service.client;

import com.project.payflo.common_lib.cache.ApiKeyCacheEntry;
import com.project.payflo.common_lib.enums.MerchantStatus;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "merchant-service", path = "/internal", url = "${MERCHANT_SERVICE_URI:}")
public interface ApiKeyLookupClient {

    @GetMapping("/api-keys/{keyId}")
    ApiKeyCacheEntry findByKeyId(@PathVariable String keyId);

    @GetMapping("/merchants/{merchantId}/status")
    MerchantStatus findMerchantStatus(@PathVariable UUID merchantId);
}
