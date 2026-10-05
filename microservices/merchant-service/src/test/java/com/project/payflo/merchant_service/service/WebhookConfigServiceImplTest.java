package com.project.payflo.merchant_service.service;

import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.common_lib.util.WebhookUrlValidator;
import com.project.payflo.merchant_service.dto.request.UpdateWebhookConfigRequest;
import com.project.payflo.merchant_service.dto.response.WebhookConfigResponse;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.entity.MerchantWebhookConfig;
import com.project.payflo.merchant_service.mapper.WebhookConfigMapper;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import com.project.payflo.merchant_service.repository.WebhookConfigRepository;
import com.project.payflo.merchant_service.service.impl.WebhookConfigServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import com.project.payflo.merchant_service.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.encrypt.Encryptors;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookConfigServiceImplTest {

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final WebhookConfigRepository configRepository = mock(WebhookConfigRepository.class);
    private final BytesEncryptor encryptor = Encryptors.stronger("test-password", "deadbeef");
    private final WebhookConfigMapper mapper = mock(WebhookConfigMapper.class);
    private final WebhookUrlValidator urlValidator = new WebhookUrlValidator(false);

    private final AuditLogService audit = mock(AuditLogService.class);
    private final WebhookConfigServiceImpl service =
            new WebhookConfigServiceImpl(merchantRepository, configRepository, encryptor, mapper, urlValidator, audit);

    private final UUID merchantId = UUID.randomUUID();
    private MerchantWebhookConfig config;

    @BeforeEach
    void anExistingConfig() {
        Merchant merchant = Merchant.builder().id(merchantId).build();
        String oldSecret = Base64.getEncoder().encodeToString(encryptor.encrypt("old-secret".getBytes(StandardCharsets.UTF_8)));
        config = MerchantWebhookConfig.builder().id(UUID.randomUUID()).merchant(merchant)
                .targetUrl("https://93.184.216.34/hook").webhookSecret(oldSecret).enabled(true).build();
        when(configRepository.findByIdAndMerchant_Id(config.getId(), merchantId)).thenReturn(Optional.of(config));
        when(mapper.toResponse(any(), any())).thenReturn(mock(WebhookConfigResponse.class));
    }

    @Test
    void rotatingGivesANewSecretShownOnceAndStoredEncrypted() {
        String before = config.getWebhookSecret();

        service.rotateSecret(merchantId, config.getId());

        ArgumentCaptor<String> shown = ArgumentCaptor.forClass(String.class);
        verify(mapper).toResponse(any(), shown.capture());
        assertThat(shown.getValue()).isNotBlank().isNotEqualTo("old-secret");

        assertThat(config.getWebhookSecret()).isNotEqualTo(before).doesNotContain(shown.getValue());
        String decrypted = new String(encryptor.decrypt(Base64.getDecoder().decode(config.getWebhookSecret())), StandardCharsets.UTF_8);
        assertThat(decrypted).isEqualTo(shown.getValue());
    }

    @Test
    void rotatingIsAuditedAsAFactNeverWithTheSecret() {
        service.rotateSecret(merchantId, config.getId());

        verify(audit).record(AuditAction.WEBHOOK_SECRET_ROTATED, merchantId, "WEBHOOK_CONFIG", config.getId().toString(), null);
    }

    @Test
    void anotherMerchantsConfigCannotBeRotated() {
        assertThatThrownBy(() -> service.rotateSecret(UUID.randomUUID(), config.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updatingCanPauseAndResumeDeliveriesWithoutDeletingTheConfig() {
        service.update(merchantId, config.getId(), new UpdateWebhookConfigRequest("https://93.184.216.34/hook", null, false));
        assertThat(config.getEnabled()).isFalse();

        service.update(merchantId, config.getId(), new UpdateWebhookConfigRequest("https://93.184.216.34/hook", null, true));
        assertThat(config.getEnabled()).isTrue();
    }

    @Test
    void leavingEnabledOutLeavesItAsItWas() {
        config.setEnabled(false);

        service.update(merchantId, config.getId(), new UpdateWebhookConfigRequest("https://93.184.216.34/other", "ALL", null));

        assertThat(config.getEnabled()).isFalse();
        assertThat(config.getTargetUrl()).isEqualTo("https://93.184.216.34/other");
    }

    @Test
    void anUpdateToAnInternalAddressIsRefused() {
        assertThatThrownBy(() -> service.update(merchantId, config.getId(),
                new UpdateWebhookConfigRequest("https://169.254.169.254/latest/meta-data", null, null)))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(config.getTargetUrl()).isEqualTo("https://93.184.216.34/hook");
    }

    @Test
    void aNewConfigIsEnabledAndItsSecretIsShownOnce() {
        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(config.getMerchant()));
        when(configRepository.save(any(MerchantWebhookConfig.class))).thenAnswer(inv -> {
            MerchantWebhookConfig saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID()); // as persisting does
            return saved;
        });

        service.create(merchantId, new UpdateWebhookConfigRequest("https://93.184.216.34/new", null, false));

        ArgumentCaptor<MerchantWebhookConfig> saved = ArgumentCaptor.forClass(MerchantWebhookConfig.class);
        verify(configRepository).save(saved.capture());
        // `enabled` is an update-only switch: a config starts out enabled whatever was sent
        assertThat(saved.getValue().getEnabled()).isTrue();
        verify(mapper, never()).toResponse(any(), org.mockito.ArgumentMatchers.isNull());
    }
}
