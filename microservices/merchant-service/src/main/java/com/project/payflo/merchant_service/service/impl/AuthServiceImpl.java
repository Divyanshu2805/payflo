package com.project.payflo.merchant_service.service.impl;

import com.project.payflo.common_lib.enums.MerchantStatus;
import com.project.payflo.common_lib.enums.UserRole;
import com.project.payflo.common_lib.exception.AccountSuspendedException;
import com.project.payflo.common_lib.exception.InvalidCredentialsException;
import com.project.payflo.common_lib.exception.DuplicateResourceException;
import com.project.payflo.merchant_service.dto.request.LoginRequest;
import com.project.payflo.merchant_service.dto.request.MerchantSignupRequest;
import com.project.payflo.merchant_service.dto.response.LoginResponse;
import com.project.payflo.merchant_service.dto.response.MerchantResponse;
import com.project.payflo.merchant_service.entity.AppUser;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.mapper.MerchantMapper;
import com.project.payflo.merchant_service.repository.AppUserRepository;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import com.project.payflo.merchant_service.security.JwtUtil;
import com.project.payflo.merchant_service.security.LoginAttemptTracker;
import com.project.payflo.merchant_service.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final AppUserRepository appUserRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantMapper merchantMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginAttemptTracker loginAttemptTracker;

    // The hash compared against when the email isn't registered; computed once, on first use.
    private volatile String dummyHash;

    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = passwordEncoder.encode(UUID.randomUUID().toString());
            dummyHash = hash;
        }
        return hash;
    }

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

        loginAttemptTracker.requireNotLocked(request.email());

        Optional<AppUser> found = appUserRepository.findByEmail(request.email());

        // Always exactly one bcrypt check, even for an unknown email, so how long the answer takes
        // doesn't reveal whether the email is registered.
        String hash = found.map(AppUser::getPasswordHash).orElseGet(this::dummyHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);

        if (found.isEmpty() || !passwordMatches) {
            loginAttemptTracker.recordFailure(request.email());
            throw new InvalidCredentialsException();
        }
        AppUser appUser = found.get();
        loginAttemptTracker.clear(request.email());

        // Only said once the password is right, so it can't be used to discover which emails exist.
        MerchantStatus status = merchantRepository.findById(appUser.getMerchant().getId())
                .map(Merchant::getStatus)
                .orElseThrow(InvalidCredentialsException::new);
        if (status == MerchantStatus.SUSPENDED) {
            throw new AccountSuspendedException();
        }

        String token = jwtUtil.generateAccessToken(request.email(), appUser.getMerchant().getId(), appUser.getRole().toString());

        return new LoginResponse(token);
    }
}















