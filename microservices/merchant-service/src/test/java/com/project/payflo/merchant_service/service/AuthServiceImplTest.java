package com.project.payflo.merchant_service.service;

import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.MerchantStatus;
import com.project.payflo.common_lib.enums.UserRole;
import com.project.payflo.common_lib.exception.AccountSuspendedException;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.InvalidTokenException;
import com.project.payflo.common_lib.exception.InvalidCredentialsException;
import com.project.payflo.common_lib.exception.RateLimitException;
import com.project.payflo.merchant_service.dto.request.LoginRequest;
import com.project.payflo.merchant_service.dto.request.MerchantSignupRequest;
import com.project.payflo.merchant_service.dto.response.LoginResponse;
import com.project.payflo.merchant_service.dto.response.MerchantResponse;
import com.project.payflo.merchant_service.entity.AppUser;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.service.AuditLogService;
import com.project.payflo.merchant_service.service.impl.MerchantRegistrar;
import com.project.payflo.merchant_service.repository.AppUserRepository;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import com.project.payflo.merchant_service.security.JwtUtil;
import com.project.payflo.merchant_service.security.LoginAttemptTracker;
import com.project.payflo.merchant_service.security.SessionService;
import com.project.payflo.merchant_service.service.impl.AuthServiceImpl;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
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
    private final SessionService sessions = mock(SessionService.class);

    private final AuditLogService audit = mock(AuditLogService.class);
    private final MerchantRegistrar registrar = mock(MerchantRegistrar.class);
    private final AuthServiceImpl service = new AuthServiceImpl(appUserRepository, merchantRepository,
            registrar, passwordEncoder, jwtUtil, attempts, sessions, audit);

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

    // ---- login issues a session

    @Test
    void loginReturnsAnAccessTokenARefreshTokenAndHowLongTheAccessTokenLives() {
        when(passwordEncoder.matches(request.password(), "stored-hash")).thenReturn(true);
        when(sessions.issueRefreshToken(request.email())).thenReturn("refresh-1");

        LoginResponse response = service.login(request);

        assertThat(response.accessToken()).isEqualTo("jwt");
        assertThat(response.refreshToken()).isEqualTo("refresh-1");
        assertThat(response.expiresInSeconds()).isEqualTo(JwtUtil.ACCESS_TOKEN_SECONDS);
    }

    // ---- refresh

    @Test
    void aRefreshTokenIsExchangedForANewPairAndUsedUp() {
        when(sessions.consumeRefreshToken("old")).thenReturn(Optional.of(request.email()));
        when(sessions.issueRefreshToken(request.email())).thenReturn("new");

        LoginResponse response = service.refresh("old");

        assertThat(response.refreshToken()).isEqualTo("new");
        assertThat(response.accessToken()).isEqualTo("jwt");
    }

    @Test
    void anUnknownOrUsedRefreshTokenIsRefused() {
        when(sessions.consumeRefreshToken("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("nope")).isInstanceOf(InvalidTokenException.class);
        verify(jwtUtil, never()).generateAccessToken(anyString(), any(), anyString());
    }

    @Test
    void aRefreshTokenOfARemovedUserIsRefused() {
        when(sessions.consumeRefreshToken("old")).thenReturn(Optional.of("gone@example.com"));
        when(appUserRepository.findByEmail("gone@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("old")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aRefreshForAMerchantSuspendedSinceIsRefused() {
        merchant.setStatus(MerchantStatus.SUSPENDED);
        when(sessions.consumeRefreshToken("old")).thenReturn(Optional.of(request.email()));

        assertThatThrownBy(() -> service.refresh("old")).isInstanceOf(AccountSuspendedException.class);
    }

    // ---- logout

    @Test
    void logoutRevokesTheAccessTokenByItsIdAndTheRefreshToken() {
        Claims claims = mock(Claims.class);
        Date expiry = new Date(System.currentTimeMillis() + 60_000);
        when(claims.getId()).thenReturn("token-id-1");
        when(claims.getExpiration()).thenReturn(expiry);
        when(jwtUtil.verifyAccessToken("the-jwt")).thenReturn(claims);

        service.logout("Bearer the-jwt", "refresh-1");

        verify(sessions).revokeAccessToken("token-id-1", expiry);
        verify(sessions).revokeRefreshToken("refresh-1");
    }

    @Test
    void logoutWithATokenThatIsAlreadyInvalidIsNotAnError() {
        when(jwtUtil.verifyAccessToken("expired")).thenThrow(new RuntimeException("expired"));

        assertThatCode(() -> service.logout("Bearer expired", null)).doesNotThrowAnyException();
        verify(sessions, never()).revokeAccessToken(any(), any());
    }

    // ---- password change

    @Test
    void changingThePasswordStoresTheNewHashAndEndsEverySession() {
        when(passwordEncoder.matches("old-pass", "stored-hash")).thenReturn(true);
        when(passwordEncoder.matches("new-pass-123", "stored-hash")).thenReturn(false);
        when(passwordEncoder.encode("new-pass-123")).thenReturn("new-hash");

        service.changePassword(request.email(), "old-pass", "new-pass-123");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(appUserRepository).save(user);
        verify(sessions).revokeAllSessions(request.email());
    }

    @Test
    void aWrongCurrentPasswordIsCountedAndRefused() {
        when(passwordEncoder.matches("wrong", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(request.email(), "wrong", "new-pass-123"))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(attempts).recordFailure(request.email());
        verify(sessions, never()).revokeAllSessions(any());
    }

    @Test
    void theNewPasswordMustDifferFromTheCurrentOne() {
        when(passwordEncoder.matches("same-pass-1", "stored-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(request.email(), "same-pass-1", "same-pass-1"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PASSWORD_UNCHANGED"));
        assertThat(user.getPasswordHash()).isEqualTo("stored-hash");
    }

    // ---- signup

    private final MerchantSignupRequest signup = new MerchantSignupRequest("Shop", "new@example.com", "password-1", "Shop Ltd", null);

    @Test
    void aNewEmailGetsTheMerchantThatWasCreatedAndItsPasswordIsHashedOnce() {
        MerchantResponse created = new MerchantResponse(UUID.randomUUID(), "Shop", "new@example.com", "Shop Ltd", null,
                MerchantStatus.PENDING_KYC);
        when(passwordEncoder.encode("password-1")).thenReturn("hash-1");
        when(registrar.register(signup, "hash-1")).thenReturn(Optional.of(created));

        assertThat(service.signup(signup)).isSameAs(created);
        verify(passwordEncoder, times(1)).encode("password-1");
    }

    @Test
    void anEmailThatIsAlreadyRegisteredGetsAnAnswerThatLooksLikeASuccessNotAConflict() {
        when(passwordEncoder.encode("password-1")).thenReturn("hash-1");
        when(registrar.register(signup, "hash-1")).thenReturn(Optional.empty());

        MerchantResponse answer = service.signup(signup);

        // the same shape as a real signup, with an id that belongs to no account
        assertThat(answer.id()).isNotNull();
        assertThat(answer.email()).isEqualTo("new@example.com");
        assertThat(answer.name()).isEqualTo("Shop");
        assertThat(answer.merchantStatus()).isEqualTo(MerchantStatus.PENDING_KYC);
        // and the same work: the password was hashed whether or not the email was known, so the time doesn't give it away
        verify(passwordEncoder).encode("password-1");
    }

    @Test
    void aRepeatedSignupDoesNotAnswerWithTheSameIdTwice() {
        when(registrar.register(any(), anyString())).thenReturn(Optional.empty());

        assertThat(service.signup(signup).id()).isNotEqualTo(service.signup(signup).id());
    }

    @Test
    void twoSignupsForOneEmailAtTheSameInstantAreAnsweredTheSameWayWhateverTheDatabaseDid() {
        // the unique index lets one in and the other's transaction fails on commit
        when(registrar.register(any(), anyString())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        MerchantResponse answer = service.signup(signup);

        assertThat(answer.email()).isEqualTo("new@example.com");
        assertThat(answer.merchantStatus()).isEqualTo(MerchantStatus.PENDING_KYC);
    }

    @Test
    void changingAPasswordIsRecordedInTheAuditLogWithoutAnyPassword() {
        when(passwordEncoder.matches("correct horse", "stored-hash")).thenReturn(true);
        when(passwordEncoder.matches("a-new-password", "stored-hash")).thenReturn(false);
        when(passwordEncoder.encode("a-new-password")).thenReturn("new-hash");

        service.changePassword(request.email(), "correct horse", "a-new-password");

        verify(audit).record(eq(AuditAction.PASSWORD_CHANGED), eq(merchant.getId()), eq("USER"), eq(user.getId().toString()), isNull());
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
