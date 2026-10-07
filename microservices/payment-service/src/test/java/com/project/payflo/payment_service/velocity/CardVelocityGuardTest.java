package com.project.payflo.payment_service.velocity;

import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.VelocityLimitException;
import com.project.payflo.common_lib.ratelimit.VelocityCounters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CardVelocityGuardTest {

    private final VelocityCounters counters = mock(VelocityCounters.class);
    private final CardVelocityProperties properties = new CardVelocityProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CardVelocityGuard guard = new CardVelocityGuard(counters, properties, meters);

    private final UUID merchant = UUID.randomUUID();
    private final String key = "velocity:card:" + merchant;

    private void counts(long attempts, long failures) {
        when(counters.counts(key)).thenReturn(Map.of("attempts", attempts, "failures", failures));
    }

    // ---- the merchant-level rule

    @Test
    void anHonestMerchantWithAFewDeclinesIsNeverRefused() {
        counts(1_000, 100); // about one in ten, as the simulated bank declines: plenty of failures, a small share

        assertThatCode(() -> guard.requireAllowed(merchant)).doesNotThrowAnyException();
    }

    @Test
    void aMerchantWhoseCardPaymentsMostlyFailIsRefusedWithRetryAfterTheWindowEnds() {
        counts(24, 22);
        when(counters.secondsLeft(key)).thenReturn(380L);

        assertThatThrownBy(() -> guard.requireAllowed(merchant)).isInstanceOfSatisfying(VelocityLimitException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo("CARD_TESTING_SUSPECTED");
            assertThat(e.getRetryAfterSeconds()).isEqualTo(380);
        });
        assertThat(meters.counter("payflo.velocity.refused", "rule", "card-testing").count()).isEqualTo(1.0);
    }

    @Test
    void aFewFailuresAreNotEnoughHoweverHighTheShare() {
        counts(5, 5); // a new merchant whose first five cards were all declined: below min-failures (20)

        assertThatCode(() -> guard.requireAllowed(merchant)).doesNotThrowAnyException();
    }

    @Test
    void bothThresholdsMustBeReached() {
        counts(100, 20); // 20 failures but only 20%: the count alone doesn't refuse
        assertThatCode(() -> guard.requireAllowed(merchant)).doesNotThrowAnyException();

        counts(40, 20); // exactly the minimum and exactly half: refused
        assertThatThrownBy(() -> guard.requireAllowed(merchant)).isInstanceOf(VelocityLimitException.class);
    }

    @Test
    void theThresholdsCanBeChanged() {
        properties.setMinFailures(3);
        properties.setFailureRatio(0.9);
        counts(10, 3);
        assertThatCode(() -> guard.requireAllowed(merchant)).doesNotThrowAnyException();

        counts(3, 3);
        assertThatThrownBy(() -> guard.requireAllowed(merchant)).isInstanceOf(VelocityLimitException.class);
    }

    @Test
    void aMerchantWithNothingCountedIsLeftAlone() {
        when(counters.counts(key)).thenReturn(Map.of());

        assertThatCode(() -> guard.requireAllowed(merchant)).doesNotThrowAnyException();
    }

    // ---- what is counted

    @Test
    void attemptsAndDeclinesAreCountedTogetherInOneWindow() {
        guard.recordAttempt(merchant);
        guard.recordDecline(merchant, "CARD_DECLINED");

        verify(counters).increment(key, "attempts", Duration.ofMinutes(10));
        verify(counters).increment(key, "failures", Duration.ofMinutes(10));
    }

    @Test
    void anOutageOfOurOwnIsNotTheCardsFault() {
        guard.recordDecline(merchant, "VAULT_CHARGE_FAILED");

        verify(counters, never()).increment(anyString(), anyString(), any());
    }

    @Test
    void switchedOffItCountsAndRefusesNothing() {
        properties.setEnabled(false);

        guard.recordAttempt(merchant);
        guard.recordDecline(merchant, "CARD_DECLINED");
        guard.requireAllowed(merchant);
        guard.requireOrderAttemptsBelowLimit(1_000);

        verify(counters, never()).increment(anyString(), anyString(), any());
        verify(counters, never()).counts(anyString());
    }

    // ---- the order-level rule

    @Test
    void anOrderTakesFiveCardPaymentsAndNotASixth() {
        assertThatCode(() -> guard.requireOrderAttemptsBelowLimit(4)).doesNotThrowAnyException();

        assertThatThrownBy(() -> guard.requireOrderAttemptsBelowLimit(5)).isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo("ORDER_CARD_ATTEMPTS_EXCEEDED");
            assertThat(e.getMessage()).contains("new order");
        });
        assertThat(meters.counter("payflo.velocity.refused", "rule", "order-card-attempts").count()).isEqualTo(1.0);
    }
}
