package com.project.payflo.merchant_service.service;

import com.project.payflo.common_lib.enums.MerchantStatus;
import com.project.payflo.common_lib.enums.UserRole;
import com.project.payflo.common_lib.exception.AccountSuspendedException;
import com.project.payflo.common_lib.exception.InvalidCredentialsException;
import com.project.payflo.common_lib.exception.RateLimitException;
import com.project.payflo.merchant_service.dto.request.LoginRequest;
import com.project.payflo.merchant_service.entity.AppUser;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.mapper.MerchantMapper;
import com.project.payflo.merchant_service.repository.AppUserRepository;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import com.project.payflo.merchant_service.security.JwtUtil;
import com.project.payflo.merchant_service.security.LoginAttemptTracker;
import com.project.payflo.merchant_service.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final LoginAttemptTracker attempts = mock(LoginAttemptTracker.class);

    private final AuthServiceImpl service = new AuthServiceImpl(appUserRepository, merchantRepository,
            mock(MerchantMapper.class), passwordEncoder, jwtUtil, attempts);

    private final LoginRequest request = new LoginRequest("owner@example.com", "correct horse");
    private Merchant merchant;
    private AppUser user;

    @BeforeEach
    void anExistingOwner() {
        merchant = Merchant.builder().id(UUID.randomUUID()).status(MerchantStatus.ACTIVE).build();
        user = AppUser.builder().id(UUID.randomUUID()).email(request.email()).merchant(merchant)
                .passwordHash("stored-hash").role(UserRole.OWNER).build();
        when(appUserRepository.findByEmail(request.email())).thenReturn(Optional.of(user));
        when(merchantRepository.findById(merchant.getId())).thenReturn(Optional.of(merchant));
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
        when(jwtUtil.generateAccessToken(anyString(), any(), anyString())).thenReturn("jwt");
    }

    @Test
    void aCorrectPasswordGivesATokenAndClearsTheFailureCount() {
        when(passwordEncoder.matches(request.password(), "stored-hash")).thenReturn(true);

        assertThat(service.login(request).accessToken()).isEqualTo("jwt");
        verify(attempts).clear(request.email());
    }

    @Test
    void aWrongPasswordIsCountedAndRefused() {
        when(passwordEncoder.matches(request.password(), "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(request)).isInstanceOf(InvalidCredentialsException.class);
        verify(attempts).recordFailure(request.email());
        verify(jwtUtil, never()).generateAccessToken(anyString(), any(), anyString());
    }

    @Test
    void anUnknownEmailStillCostsOneBcryptCheckSoTimingDoesNotRevealIt() {
        LoginRequest stranger = new LoginRequest("nobody@example.com", "whatever");
        when(appUserRepository.findByEmail(stranger.email())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(stranger)).isInstanceOf(InvalidCredentialsException.class);

        verify(passwordEncoder, times(1)).matches(eq(stranger.password()), anyString());
        verify(attempts).recordFailure(stranger.email());
    }

    @Test
    void aSuspendedMerchantCannotLogInEvenWithTheRightPassword() {
        merchant.setStatus(MerchantStatus.SUSPENDED);
        when(passwordEncoder.matches(request.password(), "stored-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.login(request)).isInstanceOf(AccountSuspendedException.class);
        verify(jwtUtil, never()).generateAccessToken(anyString(), any(), anyString());
    }

    @Test
    void aSuspendedMerchantWithAWrongPasswordLearnsNothingAboutTheSuspension() {
        merchant.setStatus(MerchantStatus.SUSPENDED);
        when(passwordEncoder.matches(request.password(), "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(request)).isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void aLockedAccountIsRefusedBeforeAnythingIsLookedUp() {
        doThrow(new RateLimitException("Too many failed login attempts, try again later", 600))
                .when(attempts).requireNotLocked(request.email());

        assertThatThrownBy(() -> service.login(request)).isInstanceOf(RateLimitException.class);
        verify(appUserRepository, never()).findByEmail(anyString());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
