package com.project.payflo.merchant_service.service.impl;

import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.MerchantStatus;
import com.project.payflo.common_lib.exception.AccountSuspendedException;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.InvalidCredentialsException;
import com.project.payflo.common_lib.exception.InvalidTokenException;
import com.project.payflo.merchant_service.dto.request.LoginRequest;
import com.project.payflo.merchant_service.dto.request.MerchantSignupRequest;
import com.project.payflo.merchant_service.dto.response.LoginResponse;
import com.project.payflo.merchant_service.dto.response.MerchantResponse;
import com.project.payflo.merchant_service.entity.AppUser;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.repository.AppUserRepository;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import com.project.payflo.merchant_service.security.JwtUtil;
import com.project.payflo.merchant_service.security.LoginAttemptTracker;
import com.project.payflo.merchant_service.security.SessionService;
import com.project.payflo.merchant_service.service.AuditLogService;
import com.project.payflo.merchant_service.service.AuthService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private static final String BEARER = "Bearer ";

    private final AppUserRepository appUserRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantRegistrar merchantRegistrar;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginAttemptTracker loginAttemptTracker;
    private final SessionService sessionService;
    private final AuditLogService auditLogService;

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

    /**
     * Signing up with an email that is already registered looks exactly like a signup that worked: the same {@code 201},
     * the same body shape, and the same work (the password is hashed either way), with an id that belongs to no account.
     * An honest {@code 409} would let anyone ask "is this email registered?" of an open endpoint, one address at a time.
     * The cost is that a person who forgot they had an account is told it was created and can only find out it wasn't by
     * logging in; there is no email to tell them, so they are told in the API docs instead.
     */
    @Override
    public MerchantResponse signup(MerchantSignupRequest request) {
        // Hashed before anything is looked up, so a known email and a new one cost the same time.
        String passwordHash = passwordEncoder.encode(request.password());
        try {
            Optional<MerchantResponse> created = merchantRegistrar.register(request, passwordHash);
            if (created.isPresent()) {
                return created.get();
            }
        } catch (DataIntegrityViolationException raced) {
            // Two signups for one email at the same moment: the unique index let one win. The other gets the same
            // answer as any other repeat.
            log.info("Signup lost a race for an email that was just registered");
        }
        log.info("Signup for an email that is already registered; answering as if it had been created");
        return decoy(request);
    }

    private MerchantResponse decoy(MerchantSignupRequest request) {
        return new MerchantResponse(UUID.randomUUID(), request.name(), request.email(), request.businessName(),
                request.businessType(), MerchantStatus.PENDING_KYC);
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
        requireNotSuspended(appUser);
        return issueSession(appUser);
    }

    @Override
    public LoginResponse refresh(String refreshToken) {
        String email = sessionService.consumeRefreshToken(refreshToken).orElseThrow(InvalidTokenException::new);

        // The user may have been removed, or the merchant suspended, since the token was issued.
        AppUser appUser = appUserRepository.findByEmail(email).orElseThrow(InvalidTokenException::new);
        requireNotSuspended(appUser);
        return issueSession(appUser);
    }

    @Override
    public void logout(String authorizationHeader, String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            sessionService.revokeRefreshToken(refreshToken);
        }
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER)) {
            return;
        }
        try {
            Claims claims = jwtUtil.verifyAccessToken(authorizationHeader.substring(BEARER.length()));
            sessionService.revokeAccessToken(claims.getId(), claims.getExpiration());
        } catch (Exception alreadyInvalid) {
            // An expired or unreadable token is already unusable; there is nothing to revoke.
            log.debug("Logout with a token that is no longer valid");
        }
    }

    @Override
    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword) {
        loginAttemptTracker.requireNotLocked(email);

        AppUser appUser = appUserRepository.findByEmail(email).orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(currentPassword, appUser.getPasswordHash())) {
            loginAttemptTracker.recordFailure(email);
            throw new InvalidCredentialsException();
        }
        loginAttemptTracker.clear(email);

        if (passwordEncoder.matches(newPassword, appUser.getPasswordHash())) {
            throw new BusinessRuleViolationException("PASSWORD_UNCHANGED", "The new password must differ from the current one");
        }

        appUser.setPasswordHash(passwordEncoder.encode(newPassword));
        appUserRepository.save(appUser);
        // That it changed, never what it changed to.
        auditLogService.record(AuditAction.PASSWORD_CHANGED, appUser.getMerchant().getId(), "USER",
                appUser.getId().toString(), null);

        // Everywhere this user is logged in, including here, has to log in again with the new password.
        sessionService.revokeAllSessions(email);
    }

    private void requireNotSuspended(AppUser appUser) {
        MerchantStatus status = merchantRepository.findById(appUser.getMerchant().getId())
                .map(Merchant::getStatus)
                .orElseThrow(InvalidCredentialsException::new);
        if (status == MerchantStatus.SUSPENDED) {
            throw new AccountSuspendedException();
        }
    }

    private LoginResponse issueSession(AppUser appUser) {
        String token = jwtUtil.generateAccessToken(appUser.getEmail(), appUser.getMerchant().getId(),
                appUser.getRole().toString());
        return new LoginResponse(token, sessionService.issueRefreshToken(appUser.getEmail()), JwtUtil.ACCESS_TOKEN_SECONDS);
    }
}
