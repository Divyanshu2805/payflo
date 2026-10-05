package com.project.payflo.merchant_service.service.impl;

import com.project.payflo.common_lib.dto.SettlementBankDetails;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.common_lib.dto.WebhookTarget;
import com.project.payflo.common_lib.enums.MerchantStatus;
import com.project.payflo.merchant_service.api.MerchantLookupService;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.entity.MerchantWebhookConfig;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import com.project.payflo.merchant_service.security.BankAccountCipher;
import com.project.payflo.merchant_service.repository.WebhookConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantLookupServiceImpl implements MerchantLookupService {

    private final MerchantRepository merchantRepository;
    private final WebhookConfigRepository merchantWebhookConfigRepository;
    private final BytesEncryptor bytesEncryptor;
    private final BankAccountCipher bankAccountCipher;

    @Override
    public List<WebhookTarget> getActiveConfigsForEvent(UUID merchantId, String eventType) {
        return merchantWebhookConfigRepository.findByMerchant_IdAndEnabledTrue(merchantId).stream()
                .filter(config -> config.isSubscribedTo(eventType))
                .map(this::toTarget)
                .toList();
    }

    @Override
    public WebhookTarget getWebhookTarget(UUID merchantId, UUID configId) {
        return merchantWebhookConfigRepository.findByIdAndMerchant_Id(configId, merchantId)
                .map(this::toTarget)
                .orElseThrow(() -> new ResourceNotFoundException("MerchantWebhookConfig", configId));
    }

    private WebhookTarget toTarget(MerchantWebhookConfig config) {
        byte[] cipherBytes = Base64.getDecoder().decode(config.getWebhookSecret());
        byte[] decryptedSecretBytes = bytesEncryptor.decrypt(cipherBytes);
        return new WebhookTarget(config.getId(), config.getTargetUrl(),
                new String(decryptedSecretBytes, StandardCharsets.UTF_8));
    }

    @Override
    public List<UUID> listActiveMerchantIds() {
        return merchantRepository.findByStatus(MerchantStatus.ACTIVE)
                .stream().map(m -> m.getId()).toList();
    }

    @Override
    public MerchantStatus getStatus(UUID merchantId) {
        return merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", merchantId))
                .getStatus();
    }

    @Override
    public SettlementBankDetails getSettlementBankDetails(UUID merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId).orElseThrow(
                () -> new ResourceNotFoundException("Merchant", merchantId));

        return new SettlementBankDetails(
                bankAccountCipher.decrypt(merchant.getSettlementBankAccount()),
                merchant.getSettlementBankIfsc(),
                merchant.getSettlementBankAccountHolderName()
        );
    }
}
