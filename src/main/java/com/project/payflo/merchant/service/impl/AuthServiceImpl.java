package com.project.payflo.merchant.service.impl;

import com.project.payflo.common.enums.MerchantStatus;
import com.project.payflo.common.enums.UserRole;
import com.project.payflo.common.exception.DuplicateResourceException;
import com.project.payflo.common.exception.ResourceNotFoundException;
import com.project.payflo.merchant.dto.request.LoginRequest;
import com.project.payflo.merchant.dto.request.MerchantSignupRequest;
import com.project.payflo.merchant.dto.response.LoginResponse;
import com.project.payflo.merchant.dto.response.MerchantResponse;
import com.project.payflo.merchant.entity.AppUser;
import com.project.payflo.merchant.entity.Merchant;
import com.project.payflo.merchant.mapper.MerchantMapper;
import com.project.payflo.merchant.repository.AppUserRepository;
import com.project.payflo.merchant.repository.MerchantRepository;
import com.project.payflo.merchant.security.JwtUtil;
import com.project.payflo.merchant.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final AppUserRepository appUserRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantMapper merchantMapper;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public MerchantResponse signup(MerchantSignupRequest request) {
        if (merchantRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("DUPLICATE_MERCHANT_EMAIL",
                    "Merchant with email already exists: " + request.email());
        }

        Merchant merchant = merchantMapper.toEntityFromSignUpRequest(request);
        merchant.setStatus(MerchantStatus.PENDING_KYC);

        merchant = merchantRepository.save(merchant);

        AppUser appUser = AppUser.builder()
                .email(request.email())
                .merchant(merchant)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.OWNER)
                .build();
        appUserRepository.save(appUser);

        return merchantMapper.toResponse(merchant);
    }

    @Override
    public LoginResponse login(LoginRequest request) {

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );

        AppUser appUser = appUserRepository.findByEmail(request.email())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.email()));

        String token = jwtUtil.generateAccessToken(request.email(), appUser.getMerchant().getId(), appUser.getRole().toString());

        return new LoginResponse(token);
    }
}















