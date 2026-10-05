package com.project.payflo.merchant_service.service;

import com.project.payflo.common_lib.cache.MerchantStatusCacheKey;
import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.MerchantStatus;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.merchant_service.dto.response.AdminMerchantResponse;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminMerchantServiceTest {

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final AdminMerchantService service = new AdminMerchantService(merchantRepository, audit, redis);

    private final UUID merchantId = UUID.randomUUID();
    private Merchant merchant;

    @BeforeEach
    void anActiveMerchant() {
        merchant = Merchant.builder().id(merchantId).name("Asha").email("asha@example.com").status(MerchantStatus.ACTIVE).build();
        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));
        when(redis.opsForValue()).thenReturn(values);
    }

    @AfterEach
    void endTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void suspendingRecordsWhyWhenAndWhatToGoBackToAndAuditsIt() {
        AdminMerchantResponse response = service.suspend(merchantId, "chargeback ring");

        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.SUSPENDED);
        assertThat(merchant.getStatusBeforeSuspension()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(merchant.getSuspendedAt()).isNotNull();
        assertThat(merchant.getSuspensionReason()).isEqualTo("chargeback ring");
        assertThat(response.status()).isEqualTo(MerchantStatus.SUSPENDED);
        verify(audit).record(AuditAction.MERCHANT_SUSPENDED, merchantId, "MERCHANT", merchantId.toString(),
                Map.of("reason", "chargeback ring", "previousStatus", "ACTIVE"));
    }

    @Test
    void suspendingTheGatewaysStatusCacheIsWrittenOnlyAfterTheCommit() {
        TransactionSynchronizationManager.initSynchronization();

        service.suspend(merchantId, "fraud");
        // still inside the transaction: writing now would let the gateway read SUSPENDED for a change that may roll back
        verify(values, never()).set(any(), any(), any(java.time.Duration.class));

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(values).set(MerchantStatusCacheKey.of(merchantId), "SUSPENDED", MerchantStatusCacheKey.TTL);
    }

    @Test
    void aRedisThatCannotBeWrittenDoesNotFailTheSuspension() {
        doThrow(new RuntimeException("redis is down")).when(values).set(any(), any(), any(java.time.Duration.class));

        assertThat(service.suspend(merchantId, "fraud").status()).isEqualTo(MerchantStatus.SUSPENDED);
    }

    @Test
    void aMerchantAlreadySuspendedCannotBeSuspendedAgain() {
        merchant.setStatus(MerchantStatus.SUSPENDED);

        assertThatThrownBy(() -> service.suspend(merchantId, "again"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("MERCHANT_ALREADY_SUSPENDED"));
        verify(audit, never()).record(any(AuditAction.class), any(), any(), any(), any());
    }

    @Test
    void reactivatingPutsTheMerchantBackToWhatItWasAndClearsTheSuspension() {
        service.suspend(merchantId, "fraud");

        AdminMerchantResponse response = service.reactivate(merchantId, "cleared");

        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(merchant.getSuspendedAt()).isNull();
        assertThat(merchant.getSuspensionReason()).isNull();
        assertThat(merchant.getStatusBeforeSuspension()).isNull();
        assertThat(response.status()).isEqualTo(MerchantStatus.ACTIVE);
        verify(audit).record(AuditAction.MERCHANT_REACTIVATED, merchantId, "MERCHANT", merchantId.toString(),
                Map.of("restoredStatus", "ACTIVE", "reason", "cleared"));
    }

    @Test
    void aMerchantSuspendedBeforeKycGoesBackToWaitingForKycNotToActive() {
        merchant.setStatus(MerchantStatus.PENDING_KYC);
        service.suspend(merchantId, "suspicious signup");

        service.reactivate(merchantId, null);

        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.PENDING_KYC);
    }

    @Test
    void aMerchantSuspendedBySomeOtherWayHasNoKnownPastSoItWaitsForKyc() {
        merchant.setStatus(MerchantStatus.SUSPENDED); // edited in the database: statusBeforeSuspension is null

        service.reactivate(merchantId, null);

        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.PENDING_KYC);
    }

    @Test
    void aMerchantThatIsNotSuspendedCannotBeReactivated() {
        assertThatThrownBy(() -> service.reactivate(merchantId, "oops"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("MERCHANT_NOT_SUSPENDED"));
    }

    @Test
    void anUnknownMerchantIsA404() {
        assertThatThrownBy(() -> service.suspend(UUID.randomUUID(), "x")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theAdminViewHasNoPayoutAccountOrPan() {
        merchant.setSettlementBankAccount("enc1:xxxx");
        merchant.setPanId("ABCDE1234F");

        assertThat(service.get(merchantId).toString()).doesNotContain("enc1:xxxx", "ABCDE1234F");
    }
}
