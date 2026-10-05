package com.project.payflo.merchant_service.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    @SuppressWarnings("unchecked")
    private final SetOperations<String, String> sets = mock(SetOperations.class);
    private final SessionService sessions = new SessionService(redis);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(sessions, "refreshTokenDays", 7);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
    }

    @Test
    void aRefreshTokenIsStoredOnlyAsAHashForTheUserForAWeek() {
        String raw = sessions.issueRefreshToken("Owner@Example.com");

        assertThat(raw).isNotBlank();
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(values).set(key.capture(), eq("owner@example.com"), eq(Duration.ofDays(7)));
        assertThat(key.getValue()).startsWith("refresh:").doesNotContain(raw);
    }

    @Test
    void twoTokensAreDifferent() {
        assertThat(sessions.issueRefreshToken("a@example.com")).isNotEqualTo(sessions.issueRefreshToken("a@example.com"));
    }

    @Test
    void ifRedisIsDownLoginStillWorksJustWithoutARefreshToken() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertThat(sessions.issueRefreshToken("a@example.com")).isNull();
    }

    @Test
    void usingARefreshTokenReturnsItsUserAndDeletesItInOneStep() {
        when(values.getAndDelete(anyString())).thenReturn("owner@example.com");

        Optional<String> email = sessions.consumeRefreshToken("raw-token");

        assertThat(email).contains("owner@example.com");
        verify(values).getAndDelete(org.mockito.ArgumentMatchers.startsWith("refresh:"));
    }

    @Test
    void anUnknownOrAlreadyUsedRefreshTokenIsEmpty() {
        when(values.getAndDelete(anyString())).thenReturn(null);

        assertThat(sessions.consumeRefreshToken("raw-token")).isEmpty();
        verify(sets, never()).remove(anyString(), any());
    }

    @Test
    void revokingAnAccessTokenKeepsItRevokedUntilItWouldHaveExpired() {
        sessions.revokeAccessToken("token-1", new Date(System.currentTimeMillis() + 120_000));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).set(eq("jwt:revoked:token-1"), eq("1"), ttl.capture());
        assertThat(ttl.getValue().toSeconds()).isBetween(119L, 122L);
    }

    @Test
    void aTokenWithNoIdCannotBeRevoked() {
        sessions.revokeAccessToken(null, new Date());

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void endingAllSessionsRefusesOlderTokensAndDeletesTheRefreshTokens() {
        when(sets.members("refresh:user:owner@example.com")).thenReturn(Set.of("hash-1", "hash-2"));

        sessions.revokeAllSessions("Owner@Example.com");

        // the key and the unit (epoch millis) the gateway reads
        ArgumentCaptor<String> cutoff = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("jwt:user-revoked-after:owner@example.com"), cutoff.capture(), any(Duration.class));
        assertThat(Long.parseLong(cutoff.getValue())).isBetween(System.currentTimeMillis() - 5_000, System.currentTimeMillis() + 1_000);
        verify(redis).delete("refresh:hash-1");
        verify(redis).delete("refresh:hash-2");
        verify(redis).delete("refresh:user:owner@example.com");
    }

    @Test
    void revocationFailuresAreNotHiddenSoALogoutCannotSilentlyDoNothing() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> sessions.revokeAccessToken("token-1", new Date(System.currentTimeMillis() + 60_000)))
                .isInstanceOf(RuntimeException.class);
    }
}
