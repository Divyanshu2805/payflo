package com.project.payflo.merchant_service.service.impl;

import com.project.payflo.common_lib.enums.MerchantStatus;
import com.project.payflo.common_lib.enums.UserRole;
import com.project.payflo.merchant_service.dto.request.MerchantSignupRequest;
import com.project.payflo.merchant_service.dto.response.MerchantResponse;
import com.project.payflo.merchant_service.entity.AppUser;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.mapper.MerchantMapper;
import com.project.payflo.merchant_service.repository.AppUserRepository;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The database side of a signup: one transaction that creates the merchant and its owner. A separate bean so
 * {@link AuthServiceImpl} can see the transaction end — including a unique-constraint violation, which only surfaces
 * at commit — and answer it the same way as an email it already knew about.
 */
@Component
@RequiredArgsConstructor
public class MerchantRegistrar {

    private final MerchantRepository merchantRepository;
    private final AppUserRepository appUserRepository;
    private final MerchantMapper merchantMapper;

    /** The new merchant, or empty if the email already logs in to an account (as an owner or a team member). */
    @Transactional
    public Optional<MerchantResponse> register(MerchantSignupRequest request, String passwordHash) {
        if (merchantRepository.existsByEmail(request.email()) || appUserRepository.findByEmail(request.email()).isPresent()) {
            return Optional.empty();
        }

        Merchant merchant = merchantMapper.toEntityFromSignUpRequest(request);
        merchant.setStatus(MerchantStatus.PENDING_KYC);
        merchant = merchantRepository.save(merchant);

        appUserRepository.save(AppUser.builder()
                .email(request.email())
                .merchant(merchant)
                .passwordHash(passwordHash)
                .role(UserRole.OWNER)
                .build());

        return Optional.of(merchantMapper.toResponse(merchant));
    }
}
