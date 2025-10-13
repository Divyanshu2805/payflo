package com.project.payflo.merchant.service.impl;

import com.project.payflo.common.enums.MerchantStatus;
import com.project.payflo.common.enums.UserRole;
import com.project.payflo.merchant.dto.request.MerchantSignupRequest;
import com.project.payflo.merchant.dto.response.MerchantResponse;
import com.project.payflo.merchant.entity.AppUser;
import com.project.payflo.merchant.entity.Merchant;
import com.project.payflo.merchant.mapper.MerchantMapper;
import com.project.payflo.merchant.repository.AppUserRepository;
import com.project.payflo.merchant.repository.MerchantRepository;
import com.project.payflo.merchant.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final AppUserRepository appUserRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantMapper merchantMapper;

    @Override
    @Transactional
    public MerchantResponse signup(MerchantSignupRequest request) {
        if (merchantRepository.existsByEmail(request.email())) {
            throw new RuntimeException("Merchant with email already exists: " + request.email());
        }

        Merchant merchant = merchantMapper.toEntityFromSignUpRequest(request);
        merchant.setStatus(MerchantStatus.PENDING_KYC);

        merchant = merchantRepository.save(merchant);

        AppUser appUser = AppUser.builder()
                .email(request.email())
                .merchant(merchant)
                .passwordHash(request.password())
                .role(UserRole.OWNER)
                .build();
        appUserRepository.save(appUser);

        return merchantMapper.toResponse(merchant);
    }
}















