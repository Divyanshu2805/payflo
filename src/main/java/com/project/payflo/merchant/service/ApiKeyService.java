package com.project.payflo.merchant.service;

import com.project.payflo.merchant.dto.request.CreateApiKeyRequest;
import com.project.payflo.merchant.dto.response.ApiKeyCreateResponse;
import java.util.UUID;

public interface ApiKeyService {

    ApiKeyCreateResponse create(UUID merchantId, CreateApiKeyRequest request);
}
