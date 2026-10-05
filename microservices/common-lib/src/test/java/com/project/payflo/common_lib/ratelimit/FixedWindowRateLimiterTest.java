package com.project.payflo.common_lib.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class FixedWindowRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(redis);

    private void redisReturns(Object result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void aRequestWithinTheLimitIsAllowedAndReportsWhatIsLeft() {
        redisReturns(List.of(3L, 40L));

        RateLimitResult result = limiter.check("key", 10, 60);

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(7);
    }

    @Test
    void theRequestAtTheLimitIsStillAllowed() {
        redisReturns(List.of(10L, 5L));

        assertThat(limiter.check("key", 10, 60).isAllowed()).isTrue();
    }

    @Test
    void aRequestOverTheLimitIsDeniedWithTheTimeLeftInTheWindow() {
        redisReturns(List.of(11L, 25L));

        RateLimitResult result = limiter.check("key", 10, 60);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.retryAfterSeconds()).isEqualTo(25);
    }

    @Test
    void aCounterWithNoExpiryFallsBackToTheWholeWindow() {
        redisReturns(List.of(11L, -1L));

        assertThat(limiter.check("key", 10, 60).retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void whenRedisIsDownTheRequestIsAllowed() {
        doThrow(new RedisConnectionFailureException("down")).when(redis).execute(any(RedisScript.class), anyList(), any());

        assertThat(limiter.check("key", 10, 60).isAllowed()).isTrue();
    }

    @Test
    void anEmptyAnswerIsTreatedAsRedisBeingUnavailable() {
        redisReturns(null);

        assertThat(limiter.check("key", 10, 60).isAllowed()).isTrue();
    }
}
