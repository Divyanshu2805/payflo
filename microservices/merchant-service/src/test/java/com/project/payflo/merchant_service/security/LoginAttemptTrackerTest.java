package com.project.payflo.merchant_service.security;

import com.project.payflo.common_lib.exception.RateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginAttemptTrackerTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final LoginAttemptTracker tracker = new LoginAttemptTracker(redis, 10, 15);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void anAccountBelowTheLimitIsNotLocked() {
        when(values.get("login:failed:owner@example.com")).thenReturn("9");

        assertThatCode(() -> tracker.requireNotLocked("owner@example.com")).doesNotThrowAnyException();
    }

    @Test
    void anAccountAtTheLimitIsLockedAndToldHowLongToWait() {
        when(values.get("login:failed:owner@example.com")).thenReturn("10");
        when(redis.getExpire("login:failed:owner@example.com")).thenReturn(540L);

        assertThatThrownBy(() -> tracker.requireNotLocked("owner@example.com"))
                .isInstanceOfSatisfying(RateLimitException.class, e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(540));
    }

    @Test
    void theEmailIsNormalizedSoCaseDoesNotDodgeTheLock() {
        when(values.get("login:failed:owner@example.com")).thenReturn("10");

        assertThatThrownBy(() -> tracker.requireNotLocked("  Owner@Example.COM "))
                .isInstanceOf(RateLimitException.class);
    }

    @Test
    void redisTroubleNeverStopsALogin() {
        when(values.get(any())).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> tracker.requireNotLocked("owner@example.com")).doesNotThrowAnyException();
    }

    @Test
    void theFirstFailureStartsTheLockWindow() {
        when(values.increment("login:failed:owner@example.com")).thenReturn(1L);

        tracker.recordFailure("owner@example.com");

        verify(redis).expire("login:failed:owner@example.com", Duration.ofMinutes(15));
    }

    @Test
    void laterFailuresDoNotExtendTheWindow() {
        when(values.increment("login:failed:owner@example.com")).thenReturn(4L);

        tracker.recordFailure("owner@example.com");

        verify(redis, never()).expire(any(String.class), any(Duration.class));
    }

    @Test
    void aSuccessfulLoginClearsTheCount() {
        tracker.clear("owner@example.com");

        verify(redis).delete("login:failed:owner@example.com");
    }
}
