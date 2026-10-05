package com.project.payflo.merchant_service.service;

import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.MerchantStatus;
import com.project.payflo.common_lib.enums.UserRole;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.ForbiddenException;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A merchant's own details: profile, payout account, and the KYC step that activates it.
 *
 * <p>Reading is open to any caller of the merchant. Changing anything needs a dashboard login with the right
 * role (see {@link CallerPolicy}), and the payout account and KYC are the owner's alone — the payout account
 * also needs the password again, since changing it redirects the money.
 *
 * <p>KYC here is <b>simulated</b>, like the bank: there is no document check and no reviewer. A merchant that
 * has filled in its profile and payout account is activated, except for the PAN reserved as the rejection
 * trigger. A real provider (or a back-office approval) would replace {@link #verify}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantProfileService {

    /** A PAN that the simulated KYC always rejects, for testing the rejection path. */
    public static final String REJECTED_PAN = "AAAAA0000A";

    private final MerchantRepository merchantRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final BankAccountCipher bankAccountCipher;
    private final CallerPolicy callerPolicy;
    private final LoginAttemptTracker loginAttemptTracker;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public MerchantProfileResponse get(UUID merchantId) {
        return toResponse(require(merchantId));
    }

    @Transactional
    public MerchantProfileResponse update(UUID merchantId, UpdateProfileRequest request) {
        callerPolicy.requireRole(UserRole.OWNER, UserRole.ADMIN);
        Merchant merchant = require(merchantId);

        if (request.name() != null) merchant.setName(request.name());
        if (request.businessName() != null) merchant.setBusinessName(request.businessName());
        if (request.businessType() != null) merchant.setBusinessType(request.businessType());
        if (request.contactNumber() != null) merchant.setContactNumber(request.contactNumber());
        if (request.websiteUrl() != null) merchant.setWebsiteUrl(request.websiteUrl());
        if (request.gstId() != null) merchant.setGstId(request.gstId());
        if (request.panId() != null) merchant.setPanId(request.panId());

        return toResponse(merchantRepository.save(merchant));
    }

    @Transactional
    public MerchantProfileResponse updateSettlementBank(UUID merchantId, SettlementBankRequest request) {
        String email = callerPolicy.requireRole(UserRole.OWNER);
        confirmPassword(email, request.currentPassword());

        Merchant merchant = require(merchantId);
        merchant.setSettlementBankAccount(bankAccountCipher.encrypt(request.accountNumber()));
        merchant.setSettlementBankIfsc(request.ifsc());
        merchant.setSettlementBankAccountHolderName(request.accountHolderName());
        merchantRepository.save(merchant);

        // Who changed it and when, never the number itself: the audit entry holds the masked number only.
        auditLogService.record(AuditAction.SETTLEMENT_BANK_CHANGED, merchantId, "MERCHANT", merchantId.toString(),
                Map.of("account", BankAccountCipher.mask(request.accountNumber()), "ifsc", request.ifsc()));
        log.warn("Settlement account changed for merchant {} by {}", merchantId, email);
        return toResponse(merchant);
    }

    /** Submits the merchant for KYC and, if it passes, activates it so it can be settled. */
    @Transactional
    public MerchantProfileResponse submitKyc(UUID merchantId) {
        callerPolicy.requireRole(UserRole.OWNER);
        Merchant merchant = require(merchantId);

        if (merchant.getStatus() == MerchantStatus.ACTIVE) {
            return toResponse(merchant);
        }
        if (merchant.getStatus() == MerchantStatus.SUSPENDED) {
            throw new BusinessRuleViolationException("KYC_NOT_ALLOWED", "A suspended merchant cannot be activated");
        }

        verify(merchant);
        merchant.setStatus(MerchantStatus.ACTIVE);
        auditLogService.record(AuditAction.KYC_VERIFIED, merchantId, "MERCHANT", merchantId.toString(), null);
        log.info("Merchant {} passed KYC and is now ACTIVE", merchantId);
        return toResponse(merchantRepository.save(merchant));
    }

    // The stand-in for a KYC provider.
    private void verify(Merchant merchant) {
        List<String> missing = new ArrayList<>();
        if (isBlank(merchant.getBusinessName())) missing.add("businessName");
        if (merchant.getBusinessType() == null) missing.add("businessType");
        if (isBlank(merchant.getContactNumber())) missing.add("contactNumber");
        if (isBlank(merchant.getPanId())) missing.add("panId");
        if (isBlank(merchant.getSettlementBankAccount()) || isBlank(merchant.getSettlementBankIfsc())
                || isBlank(merchant.getSettlementBankAccountHolderName())) {
            missing.add("settlement bank account");
        }
        if (!missing.isEmpty()) {
            throw new BusinessRuleViolationException("KYC_INCOMPLETE", "Complete these first: " + String.join(", ", missing));
        }
        if (REJECTED_PAN.equals(merchant.getPanId())) {
            throw new BusinessRuleViolationException("KYC_REJECTED", "KYC failed: the PAN could not be verified");
        }
    }

    private void confirmPassword(String email, String password) {
        loginAttemptTracker.requireNotLocked(email);
        AppUser user = appUserRepository.findByEmail(email)
                .orElseThrow(() -> new ForbiddenException("INCORRECT_PASSWORD", "The password is incorrect"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            loginAttemptTracker.recordFailure(email);
            // Not a 401: the session is fine, it is this confirmation that failed.
            throw new ForbiddenException("INCORRECT_PASSWORD", "The password is incorrect");
        }
        loginAttemptTracker.clear(email);
    }

    private Merchant require(UUID merchantId) {
        return merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", merchantId));
    }

    private MerchantProfileResponse toResponse(Merchant m) {
        MerchantProfileResponse.SettlementBank bank = isBlank(m.getSettlementBankAccount()) ? null
                : new MerchantProfileResponse.SettlementBank(
                        BankAccountCipher.mask(bankAccountCipher.decrypt(m.getSettlementBankAccount())),
                        m.getSettlementBankIfsc(), m.getSettlementBankAccountHolderName());
        return new MerchantProfileResponse(m.getId(), m.getName(), m.getEmail(), m.getBusinessName(),
                m.getBusinessType(), m.getContactNumber(), m.getWebsiteUrl(), m.getGstId(),
                BankAccountCipher.mask(m.getPanId()), m.getStatus(), bank);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
