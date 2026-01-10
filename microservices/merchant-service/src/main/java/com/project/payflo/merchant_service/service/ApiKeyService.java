package com.project.payflo.merchant_service.service;

import com.project.payflo.merchant_service.dto.request.CreateApiKeyRequest;
import com.project.payflo.merchant_service.dto.response.ApiKeyCreateResponse;
import com.project.payflo.merchant_service.dto.response.ApiKeyResponse;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public interface ApiKeyService {

    ApiKeyCreateResponse create(UUID merchantId, CreateApiKeyRequest request);

    List<ApiKeyResponse> listByMerchant(UUID merchantId);

    void revoke(UUID merchantId, UUID keyId);

    @Nullable ApiKeyCreateResponse rotate(UUID merchantId, UUID keyId);
}
