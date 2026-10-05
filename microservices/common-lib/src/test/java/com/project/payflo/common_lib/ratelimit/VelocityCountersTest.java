package com.project.payflo.common_lib.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VelocityCountersTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hash = mock(HashOperations.class);
    private final VelocityCounters counters = new VelocityCounters(redis);

    private static final Duration WINDOW = Duration.ofMinutes(10);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void redisWorks() {
        when(redis.opsForHash()).thenReturn((HashOperations) hash);
    }

    @Test
    void theFirstCountStartsTheWindow() {
        redisWorks();
        when(hash.increment("k", "attempts", 1)).thenReturn(1L);
        when(redis.getExpire("k")).thenReturn(-1L); // no expiry yet

        assertThat(counters.increment("k", "attempts", WINDOW)).isEqualTo(1);

        verify(redis).expire("k", WINDOW);
    }

    @Test
    void laterCountsDoNotExtendTheWindow() {
        redisWorks();
        when(hash.increment("k", "failures", 1)).thenReturn(7L);
        when(redis.getExpire("k")).thenReturn(321L);

        assertThat(counters.increment("k", "failures", WINDOW)).isEqualTo(7);

        verify(redis, never()).expire(any(), any(Duration.class));
    }

    @Test
    void aKeyThatLostItsExpiryInACrashGetsOneAgain() {
        redisWorks();
        when(hash.increment("k", "attempts", 5)).thenReturn(5L);
        when(redis.getExpire("k")).thenReturn(-1L);

        counters.increment("k", "attempts", WINDOW);

        verify(redis).expire("k", WINDOW);
    }

    @Test
    void theCountsOfAKeyAreReadTogether() {
        redisWorks();
        when(hash.entries("k")).thenReturn(Map.of("attempts", "24", "failures", "22"));

        assertThat(counters.counts("k")).containsEntry("attempts", 24L).containsEntry("failures", 22L);
    }

    @Test
    void secondsLeftIsAtLeastOne() {
        when(redis.getExpire("k")).thenReturn(380L);
        assertThat(counters.secondsLeft("k")).isEqualTo(380);

        when(redis.getExpire("k")).thenReturn(-2L); // gone
        assertThat(counters.secondsLeft("k")).isEqualTo(1);
    }

    @Test
    void whenRedisCannotBeReachedNothingIsCountedAndNothingIsFoundSoNothingIsRefused() {
        when(redis.opsForHash()).thenThrow(new IllegalStateException("redis is down"));
        when(redis.getExpire("k")).thenThrow(new IllegalStateException("redis is down"));

        assertThat(counters.increment("k", "attempts", WINDOW)).isZero();
        assertThat(counters.counts("k")).isEmpty();
        assertThat(counters.secondsLeft("k")).isEqualTo(1);
    }
}
