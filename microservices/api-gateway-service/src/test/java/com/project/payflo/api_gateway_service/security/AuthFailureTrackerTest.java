package com.project.payflo.api_gateway_service.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthFailureTrackerTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SecurityRouteProperties properties = new SecurityRouteProperties(); // 30 failures per minute
    private final AuthFailureTracker tracker = new AuthFailureTracker(redis, properties);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void anAddressWithFewFailuresIsNotBlocked() {
        when(values.get("authfail:1.2.3.4")).thenReturn("29");

        assertThat(tracker.blockedForSeconds("1.2.3.4")).isZero();
    }

    @Test
    void anAddressAtTheLimitIsBlockedForTheRestOfTheWindow() {
        when(values.get("authfail:1.2.3.4")).thenReturn("30");
        when(redis.getExpire("authfail:1.2.3.4")).thenReturn(41L);

        assertThat(tracker.blockedForSeconds("1.2.3.4")).isEqualTo(41);
    }

    @Test
    void anUnknownAddressIsNotBlocked() {
        when(values.get("authfail:5.6.7.8")).thenReturn(null);

        assertThat(tracker.blockedForSeconds("5.6.7.8")).isZero();
    }

    @Test
    void redisTroubleNeverBlocksAnAttempt() {
        when(values.get(any())).thenThrow(new RuntimeException("redis down"));

        assertThat(tracker.blockedForSeconds("1.2.3.4")).isZero();
    }

    @Test
    void theFirstFailureStartsTheWindow() {
        when(values.increment("authfail:1.2.3.4")).thenReturn(1L);

        tracker.recordFailure("1.2.3.4");

        verify(redis).expire("authfail:1.2.3.4", Duration.ofMinutes(1));
    }

    @Test
    void laterFailuresDoNotExtendTheWindow() {
        when(values.increment("authfail:1.2.3.4")).thenReturn(5L);

        tracker.recordFailure("1.2.3.4");

        verify(redis, never()).expire(any(String.class), any(Duration.class));
    }
}
