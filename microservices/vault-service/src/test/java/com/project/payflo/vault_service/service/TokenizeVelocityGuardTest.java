package com.project.payflo.vault_service.service;

import com.project.payflo.common_lib.exception.VelocityLimitException;
import com.project.payflo.common_lib.ratelimit.VelocityCounters;
import com.project.payflo.vault_service.config.VelocityProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenizeVelocityGuardTest {

    private final VelocityCounters counters = mock(VelocityCounters.class);
    private final VelocityProperties properties = new VelocityProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TokenizeVelocityGuard guard = new TokenizeVelocityGuard(counters, properties, meters);

    private final UUID merchant = UUID.randomUUID();

    private void counts(long perMinute, long perHour) {
        when(counters.increment(eq(TokenizeVelocityGuard.MINUTE_KEY + merchant), anyString(), eq(Duration.ofMinutes(1)))).thenReturn(perMinute);
        when(counters.increment(eq(TokenizeVelocityGuard.HOUR_KEY + merchant), anyString(), eq(Duration.ofHours(1)))).thenReturn(perHour);
    }

    @Test
    void aMerchantUnderBothLimitsIsLeftAlone() {
        counts(30, 600); // exactly at the limits is still allowed

        assertThatCode(() -> guard.requireAllowed(merchant)).doesNotThrowAnyException();
    }

    @Test
    void theBurstLimitRefusesWithRetryAfterTheWindowEnds() {
        counts(31, 31);
        when(counters.secondsLeft(TokenizeVelocityGuard.MINUTE_KEY + merchant)).thenReturn(42L);

        assertThatThrownBy(() -> guard.requireAllowed(merchant)).isInstanceOfSatisfying(VelocityLimitException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo("CARD_TOKENIZATION_LIMIT_EXCEEDED");
            assertThat(e.getRetryAfterSeconds()).isEqualTo(42);
        });
        assertThat(meters.counter("payflo.velocity.refused", "rule", "tokenize-per-minute").count()).isEqualTo(1.0);
    }

    @Test
    void theSustainedLimitCatchesASlowTrickleTheBurstLimitMisses() {
        counts(5, 601);
        when(counters.secondsLeft(TokenizeVelocityGuard.HOUR_KEY + merchant)).thenReturn(1800L);

        assertThatThrownBy(() -> guard.requireAllowed(merchant)).isInstanceOfSatisfying(VelocityLimitException.class,
                e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(1800));
        assertThat(meters.counter("payflo.velocity.refused", "rule", "tokenize-per-hour").count()).isEqualTo(1.0);
    }

    @Test
    void theLimitsCanBeChanged() {
        properties.setTokenizePerMinute(2);
        counts(3, 3);

        assertThatThrownBy(() -> guard.requireAllowed(merchant)).isInstanceOf(VelocityLimitException.class);
    }

    @Test
    void switchedOffItCountsNothingAndRefusesNothing() {
        properties.setEnabled(false);

        guard.requireAllowed(merchant);

        verify(counters, never()).increment(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aMissingMerchantIsNotCountedUnderSomeSharedKey() {
        guard.requireAllowed(null);

        verify(counters, never()).increment(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void whenRedisCannotBeReachedTheCountsAreZeroAndNothingIsRefused() {
        // VelocityCounters returns 0 when it can't count (it fails open), which is under every limit
        when(counters.increment(anyString(), anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(0L);

        assertThatCode(() -> guard.requireAllowed(merchant)).doesNotThrowAnyException();
    }
}
