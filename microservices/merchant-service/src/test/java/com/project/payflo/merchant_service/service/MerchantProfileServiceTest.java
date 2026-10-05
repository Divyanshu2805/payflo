package com.project.payflo.merchant_service.service;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.BusinessType;
import com.project.payflo.common_lib.enums.MerchantStatus;
import com.project.payflo.common_lib.enums.UserRole;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.ForbiddenException;
import com.project.payflo.merchant_service.dto.request.MerchantProfileRequests.SettlementBankRequest;
import com.project.payflo.merchant_service.dto.request.MerchantProfileRequests.UpdateProfileRequest;
import com.project.payflo.merchant_service.dto.response.MerchantProfileResponse;
import com.project.payflo.merchant_service.entity.AppUser;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.repository.AppUserRepository;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import com.project.payflo.merchant_service.security.BankAccountCipher;
import com.project.payflo.merchant_service.security.CallerPolicy;
import com.project.payflo.merchant_service.security.LoginAttemptTracker;
import org.junit.jupiter.api.BeforeEach;
import com.project.payflo.merchant_service.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantProfileServiceTest {

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final LoginAttemptTracker attempts = mock(LoginAttemptTracker.class);
    private final BankAccountCipher cipher = new BankAccountCipher(Encryptors.stronger("test-password", "deadbeef"));
    private final MerchantContext context = new MerchantContext();

    private final AuditLogService audit = mock(AuditLogService.class);
    private final MerchantProfileService service = new MerchantProfileService(
            merchantRepository, appUserRepository, passwordEncoder, cipher, new CallerPolicy(context), attempts, audit);

    private Merchant merchant;

    private void callerIs(String role) {
        context.setUserEmail("owner@example.com");
        context.setUserRole(role);
    }

    @BeforeEach
    void anOwnerOfANewMerchant() {
        merchant = Merchant.builder().id(UUID.randomUUID()).name("Shop").email("owner@example.com")
                .status(MerchantStatus.PENDING_KYC).build();
        when(merchantRepository.findById(merchant.getId())).thenReturn(Optional.of(merchant));
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        AppUser owner = AppUser.builder().email("owner@example.com").passwordHash("hash").role(UserRole.OWNER).build();
        when(appUserRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(owner));
        when(passwordEncoder.matches("right-password", "hash")).thenReturn(true);
        callerIs("OWNER");
    }

    private SettlementBankRequest bankRequest(String password) {
        return new SettlementBankRequest("123456789012", "HDFC0001234", "Shop Owner", password);
    }

    private void completeTheProfile() {
        merchant.setBusinessName("Shop Pvt Ltd");
        merchant.setBusinessType(BusinessType.PRIVATE_LIMITED);
        merchant.setContactNumber("+91 9999999999");
        merchant.setPanId("ABCDE1234F");
        merchant.setSettlementBankAccount(cipher.encrypt("123456789012"));
        merchant.setSettlementBankIfsc("HDFC0001234");
        merchant.setSettlementBankAccountHolderName("Shop Owner");
    }

    // ---- reading

    @Test
    void theProfileShowsOnlyAMaskedPanAndAccountNumber() {
        completeTheProfile();
        context.setUserEmail(null); // reading is open to an API key too
        context.setUserRole(null);

        MerchantProfileResponse profile = service.get(merchant.getId());

        assertThat(profile.panId()).isEqualTo("XXXXXX234F");
        assertThat(profile.settlementBank().accountNumber()).isEqualTo("XXXXXXXX9012");
        assertThat(profile.settlementBank().ifsc()).isEqualTo("HDFC0001234");
    }

    @Test
    void aMerchantWithNoPayoutAccountShowsNone() {
        assertThat(service.get(merchant.getId()).settlementBank()).isNull();
    }

    // ---- profile

    @Test
    void ownersAndAdminsCanChangeTheProfileAndOnlyTheFieldsSent() {
        merchant.setBusinessName("Old name");
        callerIs("ADMIN");

        service.update(merchant.getId(), new UpdateProfileRequest(null, "New name", null, "+91 8888888888", null, null, null));

        assertThat(merchant.getBusinessName()).isEqualTo("New name");
        assertThat(merchant.getContactNumber()).isEqualTo("+91 8888888888");
        assertThat(merchant.getName()).isEqualTo("Shop");
    }

    @Test
    void aTeamMemberOrAnApiKeyCannotChangeTheProfile() {
        UpdateProfileRequest change = new UpdateProfileRequest("X", null, null, null, null, null, null);

        callerIs("TEAM");
        assertThatThrownBy(() -> service.update(merchant.getId(), change)).isInstanceOfSatisfying(ForbiddenException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("ROLE_FORBIDDEN"));

        context.setUserEmail(null);
        context.setUserRole(null);
        assertThatThrownBy(() -> service.update(merchant.getId(), change)).isInstanceOfSatisfying(ForbiddenException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("DASHBOARD_LOGIN_REQUIRED"));
        verify(merchantRepository, never()).save(any());
    }

    // ---- payout account

    @Test
    void theOwnerCanSetThePayoutAccountWithThePasswordAndItIsStoredEncrypted() {
        service.updateSettlementBank(merchant.getId(), bankRequest("right-password"));

        assertThat(merchant.getSettlementBankAccount()).startsWith("enc1:").doesNotContain("123456789012");
        assertThat(cipher.decrypt(merchant.getSettlementBankAccount())).isEqualTo("123456789012");
        assertThat(merchant.getSettlementBankIfsc()).isEqualTo("HDFC0001234");
        verify(attempts).clear("owner@example.com");
    }

    @Test
    void changingThePayoutAccountIsAuditedWithTheMaskedNumberNeverTheWholeOne() {
        service.updateSettlementBank(merchant.getId(), bankRequest("right-password"));

        verify(audit).record(AuditAction.SETTLEMENT_BANK_CHANGED, merchant.getId(), "MERCHANT", merchant.getId().toString(),
                Map.of("account", "XXXXXXXX9012", "ifsc", "HDFC0001234"));
    }

    @Test
    void aRefusedPayoutAccountChangeLeavesNothingInTheAuditLog() {
        assertThatThrownBy(() -> service.updateSettlementBank(merchant.getId(), bankRequest("wrong")))
                .isInstanceOf(ForbiddenException.class);

        verify(audit, never()).record(any(AuditAction.class), any(), any(), any(), any());
    }

    @Test
    void aWrongPasswordLeavesThePayoutAccountAlone() {
        assertThatThrownBy(() -> service.updateSettlementBank(merchant.getId(), bankRequest("wrong")))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("INCORRECT_PASSWORD"));

        assertThat(merchant.getSettlementBankAccount()).isNull();
        verify(attempts).recordFailure("owner@example.com");
    }

    @Test
    void onlyTheOwnerCanChangeThePayoutAccount() {
        callerIs("ADMIN");
        assertThatThrownBy(() -> service.updateSettlementBank(merchant.getId(), bankRequest("right-password")))
                .isInstanceOf(ForbiddenException.class);

        context.setUserEmail(null);
        context.setUserRole(null);
        assertThatThrownBy(() -> service.updateSettlementBank(merchant.getId(), bankRequest("right-password")))
                .isInstanceOf(ForbiddenException.class);
        assertThat(merchant.getSettlementBankAccount()).isNull();
    }

    // ---- KYC

    @Test
    void kycNeedsTheProfileAndPayoutAccountFirst() {
        assertThatThrownBy(() -> service.submitKyc(merchant.getId()))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("KYC_INCOMPLETE");
                    assertThat(e.getMessage()).contains("panId", "settlement bank account", "businessName");
                });
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.PENDING_KYC);
    }

    @Test
    void aCompleteMerchantPassesKycAndBecomesActive() {
        completeTheProfile();

        MerchantProfileResponse profile = service.submitKyc(merchant.getId());

        verify(audit).record(AuditAction.KYC_VERIFIED, merchant.getId(), "MERCHANT", merchant.getId().toString(), null);

        assertThat(profile.status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.ACTIVE);
    }

    @Test
    void theReservedPanIsRejectedByTheSimulatedKyc() {
        completeTheProfile();
        merchant.setPanId(MerchantProfileService.REJECTED_PAN);

        assertThatThrownBy(() -> service.submitKyc(merchant.getId()))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("KYC_REJECTED"));
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.PENDING_KYC);
    }

    @Test
    void submittingKycAgainWhenActiveChangesNothing() {
        merchant.setStatus(MerchantStatus.ACTIVE);

        assertThat(service.submitKyc(merchant.getId()).status()).isEqualTo(MerchantStatus.ACTIVE);
        verify(merchantRepository, never()).save(any());
    }

    @Test
    void aSuspendedMerchantCannotBeReactivatedByKyc() {
        completeTheProfile();
        merchant.setStatus(MerchantStatus.SUSPENDED);

        assertThatThrownBy(() -> service.submitKyc(merchant.getId()))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("KYC_NOT_ALLOWED"));
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.SUSPENDED);
    }

    @Test
    void onlyTheOwnerCanSubmitKyc() {
        completeTheProfile();
        callerIs("ADMIN");

        assertThatThrownBy(() -> service.submitKyc(merchant.getId())).isInstanceOf(ForbiddenException.class);
    }
}
