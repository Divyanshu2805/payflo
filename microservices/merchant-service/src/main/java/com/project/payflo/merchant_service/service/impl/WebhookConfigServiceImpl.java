package com.project.payflo.merchant_service.service.impl;

import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.common_lib.util.RandomizerUtil;
import com.project.payflo.common_lib.util.WebhookUrlValidator;
import com.project.payflo.merchant_service.dto.request.UpdateWebhookConfigRequest;
import com.project.payflo.merchant_service.dto.response.WebhookConfigResponse;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.entity.MerchantWebhookConfig;
import com.project.payflo.merchant_service.mapper.WebhookConfigMapper;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import com.project.payflo.merchant_service.repository.WebhookConfigRepository;
import com.project.payflo.merchant_service.service.AuditLogService;
import com.project.payflo.merchant_service.service.WebhookConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookConfigServiceImpl implements WebhookConfigService {

    private final MerchantRepository merchantRepository;
    private final WebhookConfigRepository merchantWebhookConfigRepository;
    private final BytesEncryptor bytesEncryptor;
    private final WebhookConfigMapper webhookConfigMapper;
    private final WebhookUrlValidator webhookUrlValidator;
    private final AuditLogService auditLogService;

    @Override
    @Transactional
    public WebhookConfigResponse create(UUID merchantId, UpdateWebhookConfigRequest request) {
        webhookUrlValidator.validate(request.targetUrl());

        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", merchantId));

        String rawSecret = RandomizerUtil.randomBase64(32);
        byte[] rawSecretBytes = rawSecret.getBytes(StandardCharsets.UTF_8);

        String encryptedSecret = Base64.getEncoder().encodeToString
                (bytesEncryptor.encrypt(rawSecretBytes));

        MerchantWebhookConfig config = MerchantWebhookConfig.builder()
                .merchant(merchant)
                .targetUrl(request.targetUrl())
                .enabled(true)
                .eventTypes(request.eventTypes())
                .webhookSecret(encryptedSecret)
                .build();

        config = merchantWebhookConfigRepository.save(config);

        auditLogService.record(AuditAction.WEBHOOK_CONFIG_CREATED, merchantId, "WEBHOOK_CONFIG", config.getId().toString(),
                Map.of("host", hostOf(request.targetUrl())));
        return webhookConfigMapper.toResponse(config, rawSecret);
    }

    @Override
    public List<WebhookConfigResponse> list(UUID merchantId) {
        return merchantWebhookConfigRepository.findByMerchant_Id(merchantId).stream()
                .map(config -> webhookConfigMapper.toResponse(config, null))
                .toList();
    }

    @Override
    public WebhookConfigResponse getById(UUID merchantId, UUID configId) {
        MerchantWebhookConfig config = requireOwnedConfig(merchantId, configId);
        return webhookConfigMapper.toResponse(config, null);
    }

    @Override
    @Transactional
    public WebhookConfigResponse update(UUID merchantId, UUID configId, UpdateWebhookConfigRequest request) {
        webhookUrlValidator.validate(request.targetUrl());

        MerchantWebhookConfig config = requireOwnedConfig(merchantId, configId);
        Map<String, Object> changes = new LinkedHashMap<>();
        if (!Objects.equals(config.getTargetUrl(), request.targetUrl())) {
            changes.put("host", hostOf(request.targetUrl()));
        }
        if (request.enabled() != null && !request.enabled().equals(config.getEnabled())) {
            changes.put("enabled", request.enabled());
        }
        if (!Objects.equals(config.getEventTypes(), request.eventTypes())) {
            changes.put("eventTypesChanged", true);
        }
        config.setTargetUrl(request.targetUrl());
        config.setEventTypes(request.eventTypes());
        if (request.enabled() != null) {
            config.setEnabled(request.enabled());
        }
        auditLogService.record(AuditAction.WEBHOOK_CONFIG_UPDATED, merchantId, "WEBHOOK_CONFIG", configId.toString(), changes);
        log.info("Merchant webhook config updated id={} merchantId={}", configId, merchantId);
        return webhookConfigMapper.toResponse(config, null);
    }

    @Override
    @Transactional
    public void delete(UUID merchantId, UUID configId) {
        MerchantWebhookConfig config = requireOwnedConfig(merchantId, configId);
        merchantWebhookConfigRepository.delete(config);
        auditLogService.record(AuditAction.WEBHOOK_CONFIG_DELETED, merchantId, "WEBHOOK_CONFIG", configId.toString(),
                Map.of("host", hostOf(config.getTargetUrl())));
        log.info("Merchant webhook config deleted id={} merchantId={}", configId, merchantId);
    }

    // A new signing secret, effective at once and shown once. Events already created are signed with the
    // old secret, so a delivery still in its retry schedule at the moment of rotation fails verification
    // against the new one.
    @Override
    @Transactional
    public WebhookConfigResponse rotateSecret(UUID merchantId, UUID configId) {
        MerchantWebhookConfig config = requireOwnedConfig(merchantId, configId);

        String rawSecret = RandomizerUtil.randomBase64(32);
        config.setWebhookSecret(Base64.getEncoder().encodeToString(
                bytesEncryptor.encrypt(rawSecret.getBytes(StandardCharsets.UTF_8))));

        // The fact of a rotation, never the secret.
        auditLogService.record(AuditAction.WEBHOOK_SECRET_ROTATED, merchantId, "WEBHOOK_CONFIG", configId.toString(), null);
        log.info("Merchant webhook secret rotated id={} merchantId={}", configId, merchantId);
        return webhookConfigMapper.toResponse(config, rawSecret);
    }

    // The host only: a URL can carry a token in its path or query, which doesn't belong in a log.
    private static String hostOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host != null ? host : "unknown";
        } catch (IllegalArgumentException malformed) {
            return "unknown";
        }
    }

    private MerchantWebhookConfig requireOwnedConfig(UUID merchantId, UUID configId) {
        return merchantWebhookConfigRepository.findByIdAndMerchant_Id(configId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("MerchantWebhookConfig", configId));
    }
}
















