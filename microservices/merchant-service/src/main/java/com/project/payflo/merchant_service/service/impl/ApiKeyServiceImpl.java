package com.project.payflo.merchant_service.service.impl;

import com.project.payflo.common_lib.cache.ApiKeyCache;
import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.common_lib.util.RandomizerUtil;
import com.project.payflo.merchant_service.dto.request.CreateApiKeyRequest;
import com.project.payflo.merchant_service.dto.response.ApiKeyCreateResponse;
import com.project.payflo.merchant_service.dto.response.ApiKeyResponse;
import com.project.payflo.merchant_service.entity.ApiKey;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.mapper.ApiKeyMapper;
import com.project.payflo.merchant_service.repository.ApiKeyRepository;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import com.project.payflo.merchant_service.service.ApiKeyService;
import com.project.payflo.merchant_service.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ApiKeyServiceImpl implements ApiKeyService {

    private final MerchantRepository merchantRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyMapper apiKeyMapper;
    private static final int DEFAULT_GRACE_PERIOD_HOURS = 24;

    private BCryptPasswordEncoder BCRPYT = new BCryptPasswordEncoder();
    private final ApiKeyCache apiKeyCache;
    private final AuditLogService auditLogService;

    @Override
    @Transactional
    public ApiKeyCreateResponse create(UUID merchantId, CreateApiKeyRequest request) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("merchant", merchantId));

        String keyId = "pf_"+request.environment().name().toLowerCase()+"_"+ RandomizerUtil.randomBase64(24);
        String rawSecret = RandomizerUtil.randomBase64(40);

        ApiKey apiKey = ApiKey.builder()
                .merchant(merchant)
                .keyId(keyId)
                .keySecretHash(BCRPYT.encode(rawSecret))
                .environment(request.environment())
                .build();

        apiKey = apiKeyRepository.save(apiKey);

        auditLogService.record(AuditAction.API_KEY_CREATED, merchantId, "API_KEY", apiKey.getId().toString(),
                Map.of("keyId", keyId, "environment", request.environment().name()));
        return new ApiKeyCreateResponse(apiKey.getId(), keyId, rawSecret, request.environment());
    }

    @Override
    public List<ApiKeyResponse> listByMerchant(UUID merchantId) {
        return apiKeyMapper.toResponseList(apiKeyRepository.findByMerchant_Id(merchantId));
    }

    @Override
    @Transactional
    public void revoke(UUID merchantId, UUID keyId) {
        ApiKey key = apiKeyRepository.findById(keyId)
                .filter(k -> k.getMerchant().getId().equals(merchantId))
                .orElseThrow(() -> new ResourceNotFoundException("ApiKey", keyId));

        key.setEnabled(false);
        auditLogService.record(AuditAction.API_KEY_REVOKED, merchantId, "API_KEY", keyId.toString(),
                Map.of("keyId", key.getKeyId()));
        evictAfterCommit(key.getKeyId());
    }

    @Override
    @Transactional
    public ApiKeyCreateResponse rotate(UUID merchantId, UUID keyId, Integer gracePeriodHours) {
        ApiKey apiKey = apiKeyRepository.findById(keyId)
                .filter(k -> k.getMerchant().getId().equals(merchantId))
                .orElseThrow(() -> new ResourceNotFoundException("ApiKey", keyId));

        if(!apiKey.isEnabled()) {
            throw new BusinessRuleViolationException("API_KEY_REVOKED", "Cannot rotate a revoked key");
        }

        int graceHours = gracePeriodHours != null ? gracePeriodHours : DEFAULT_GRACE_PERIOD_HOURS;
        LocalDateTime now = LocalDateTime.now();

        String newRawSecret = RandomizerUtil.randomBase64(40);
        // With no grace period the old secret is dropped outright, which is what you want after a leak.
        apiKey.setPreviousKeySecretHash(graceHours > 0 ? apiKey.getKeySecretHash() : null);
        apiKey.setKeySecretHash(BCRPYT.encode(newRawSecret));
        apiKey.setRotatedAt(now);
        apiKey.setGracePeriodExpiresAt(now.plusHours(graceHours));
        apiKey = apiKeyRepository.save(apiKey);

        auditLogService.record(AuditAction.API_KEY_ROTATED, merchantId, "API_KEY", apiKey.getId().toString(),
                Map.of("keyId", apiKey.getKeyId(), "gracePeriodHours", graceHours));
        evictAfterCommit(apiKey.getKeyId());

        return new ApiKeyCreateResponse(apiKey.getId(), apiKey.getKeyId(),
                newRawSecret, apiKey.getEnvironment());
    }

    // Evicting before the transaction commits left a window in which the gateway re-read the old row
    // from the database and cached it again, so a revoked key (or a rotated-out secret) kept working
    // until that entry expired. After the commit, the next read sees the new row.
    private void evictAfterCommit(String keyId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    apiKeyCache.evict(keyId);
                }
            });
        } else {
            apiKeyCache.evict(keyId);
        }
    }

}



















