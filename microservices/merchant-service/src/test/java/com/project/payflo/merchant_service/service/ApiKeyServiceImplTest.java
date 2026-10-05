package com.project.payflo.merchant_service.service;

import com.project.payflo.common_lib.cache.ApiKeyCache;
import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.Environment;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.merchant_service.dto.response.ApiKeyCreateResponse;
import com.project.payflo.merchant_service.entity.ApiKey;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.mapper.ApiKeyMapper;
import com.project.payflo.merchant_service.repository.ApiKeyRepository;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import com.project.payflo.merchant_service.service.impl.ApiKeyServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import com.project.payflo.merchant_service.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyServiceImplTest {

    private final ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
    private final ApiKeyCache apiKeyCache = mock(ApiKeyCache.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final ApiKeyServiceImpl service = new ApiKeyServiceImpl(
            mock(MerchantRepository.class), apiKeyRepository, mock(ApiKeyMapper.class), apiKeyCache, audit);

    private final UUID merchantId = UUID.randomUUID();
    private ApiKey key;

    @BeforeEach
    void anExistingKey() {
        Merchant merchant = Merchant.builder().id(merchantId).build();
        key = ApiKey.builder().id(UUID.randomUUID()).merchant(merchant).keyId("pf_test_abc")
                .keySecretHash("old-hash").environment(Environment.TEST).build();
        when(apiKeyRepository.findById(key.getId())).thenReturn(Optional.of(key));
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void endTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rotatingKeepsTheOldSecretWorkingForADayByDefault() {
        ApiKeyCreateResponse response = service.rotate(merchantId, key.getId(), null);

        assertThat(response.keySecret()).isNotBlank();
        assertThat(key.getPreviousKeySecretHash()).isEqualTo("old-hash");
        assertThat(key.getGracePeriodExpiresAt()).isAfter(LocalDateTime.now().plusHours(23));
        assertThat(key.getKeySecretHash()).isNotEqualTo("old-hash");
    }

    @Test
    void aGracePeriodOfZeroDropsTheOldSecretImmediately() {
        service.rotate(merchantId, key.getId(), 0);

        assertThat(key.getPreviousKeySecretHash()).isNull();
        assertThat(key.isInGracePeriod()).isFalse();
    }

    @Test
    void aShorterGracePeriodIsHonoured() {
        service.rotate(merchantId, key.getId(), 2);

        assertThat(key.getGracePeriodExpiresAt()).isBetween(LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(3));
    }

    @Test
    void rotatingARevokedKeyIsA400NotA500() {
        key.setEnabled(false);

        assertThatThrownBy(() -> service.rotate(merchantId, key.getId(), null))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("API_KEY_REVOKED"));
    }

    @Test
    void anotherMerchantsKeyIsNotFound() {
        assertThatThrownBy(() -> service.rotate(UUID.randomUUID(), key.getId(), null))
                .hasMessageContaining("not found");
    }

    @Test
    void theCacheIsEvictedOnlyAfterTheTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();

        service.revoke(merchantId, key.getId());

        assertThat(key.isEnabled()).isFalse();
        // still inside the transaction: evicting now would let the gateway re-cache the old row
        verify(apiKeyCache, never()).evict("pf_test_abc");

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(apiKeyCache).evict("pf_test_abc");
    }

    @Test
    void revokingAndRotatingAreRecordedInTheAuditLogWithTheKeysPublicIdAndNeverASecret() {
        service.rotate(merchantId, key.getId(), 2);
        service.revoke(merchantId, key.getId());

        verify(audit).record(eq(AuditAction.API_KEY_ROTATED), eq(merchantId), eq("API_KEY"), eq(key.getId().toString()),
                eq(Map.of("keyId", "pf_test_abc", "gracePeriodHours", 2)));
        verify(audit).record(eq(AuditAction.API_KEY_REVOKED), eq(merchantId), eq("API_KEY"), eq(key.getId().toString()),
                eq(Map.of("keyId", "pf_test_abc")));
    }

    @Test
    void aRotationThatIsRefusedLeavesNothingInTheAuditLog() {
        key.setEnabled(false);

        assertThatThrownBy(() -> service.rotate(merchantId, key.getId(), null)).isInstanceOf(BusinessRuleViolationException.class);

        verify(audit, never()).record(any(AuditAction.class), any(), any(), any(), any());
    }

    @Test
    void rotationEvictsAfterCommitToo() {
        TransactionSynchronizationManager.initSynchronization();

        service.rotate(merchantId, key.getId(), null);
        verify(apiKeyCache, never()).evict("pf_test_abc");

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(apiKeyCache).evict("pf_test_abc");
    }
}
