package com.project.payflo.merchant.service.impl;

import com.project.payflo.common.exception.ResourceNotFoundException;
import com.project.payflo.common.util.RandomizerUtil;
import com.project.payflo.merchant.dto.request.CreateApiKeyRequest;
import com.project.payflo.merchant.dto.response.ApiKeyCreateResponse;
import com.project.payflo.merchant.dto.response.ApiKeyResponse;
import com.project.payflo.merchant.entity.ApiKey;
import com.project.payflo.merchant.entity.Merchant;
import com.project.payflo.merchant.mapper.ApiKeyMapper;
import com.project.payflo.merchant.repository.ApiKeyRepository;
import com.project.payflo.merchant.repository.MerchantRepository;
import com.project.payflo.merchant.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ApiKeyServiceImpl implements ApiKeyService {

    private final MerchantRepository merchantRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyMapper apiKeyMapper;

    @Override
    @Transactional
    public ApiKeyCreateResponse create(UUID merchantId, CreateApiKeyRequest request) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("merchant", merchantId));

        String keyId = "pfx_"+request.environment().name().toLowerCase()+"_"+ RandomizerUtil.randomBase64(24);
        String rawSecret = RandomizerUtil.randomBase64(40);

        ApiKey apiKey = ApiKey.builder()
                .merchant(merchant)
                .keyId(keyId)
                .keySecretHash(rawSecret)
                .environment(request.environment())
                .build();

        apiKey = apiKeyRepository.save(apiKey);

        return new ApiKeyCreateResponse(apiKey.getId(), keyId, rawSecret, request.environment());
    }

    @Override
    public List<ApiKeyResponse> listByMerchant(UUID merchantId) {
        return apiKeyMapper.toResponseList(apiKeyRepository.findByMerchant_Id(merchantId));
    }

}



















