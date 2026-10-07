package com.project.payflo.operations_service.settlement;

import com.project.payflo.common_lib.enums.ChaosMode;
import com.project.payflo.operations_service.settlement.PayoutOutcomeDecider.Kind;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutOutcomeDeciderTest {

    private final PayoutSimulatorProperties properties = new PayoutSimulatorProperties();
    private final PayoutOutcomeDecider decider = new PayoutOutcomeDecider(properties);

    private final LocalDateTime accepted = LocalDateTime.of(2026, 10, 6, 12, 0, 0);

    // Every transfer takes exactly 10 seconds, so the clock is the only thing that varies.
    private void tenSecondDelay() {
        properties.setMinDelaySeconds(10);
        properties.setMaxDelaySeconds(10);
    }

    private static UUID settlement(long n) {
        return new UUID(n * 0x9E3779B97F4A7C15L, n * 31 + 7);
    }

    @Test
    void theBankIsStillThinkingUntilTheDelayHasPassed() {
        tenSecondDelay();
        properties.setChaosMode(ChaosMode.SUCCESS);

        assertThat(decider.decide(settlement(1), accepted, accepted.plusSeconds(9)).kind()).isEqualTo(Kind.WAIT);
        assertThat(decider.decide(settlement(1), accepted, accepted.plusSeconds(10)).kind()).isEqualTo(Kind.SUCCESS);
    }

    @Test
    void successModePaysEverything() {
        tenSecondDelay();
        properties.setChaosMode(ChaosMode.SUCCESS);

        for (long n = 0; n < 200; n++) {
            assertThat(decider.decide(settlement(n), accepted, accepted.plusMinutes(1)).kind()).isEqualTo(Kind.SUCCESS);
        }
    }

    @Test
    void failureModeDeclinesEverythingWithACode() {
        tenSecondDelay();
        properties.setChaosMode(ChaosMode.FAILURE);

        PayoutOutcomeDecider.Outcome outcome = decider.decide(settlement(3), accepted, accepted.plusMinutes(1));

        assertThat(outcome.kind()).isEqualTo(Kind.FAILURE);
        assertThat(outcome.errorCode()).isEqualTo("SIM_PAYOUT_DECLINED");
        assertThat(outcome.errorDescription()).isNotBlank();
    }

    @Test
    void timeoutModeNeverAnswers() {
        tenSecondDelay();
        properties.setChaosMode(ChaosMode.TIMEOUT);

        assertThat(decider.decide(settlement(4), accepted, accepted.plusDays(30)).kind()).isEqualTo(Kind.WAIT);
    }

    @Test
    void slowModeTakesTwiceAsLong() {
        tenSecondDelay();
        properties.setChaosMode(ChaosMode.SLOW);
        properties.setSuccessRate(100);

        assertThat(decider.decide(settlement(5), accepted, accepted.plusSeconds(19)).kind()).isEqualTo(Kind.WAIT);
        assertThat(decider.decide(settlement(5), accepted, accepted.plusSeconds(20)).kind()).isEqualTo(Kind.SUCCESS);
    }

    @Test
    void normalModeSucceedsAtTheConfiguredRate() {
        tenSecondDelay();
        properties.setChaosMode(ChaosMode.NORMAL);
        properties.setSuccessRate(80);

        int succeeded = 0;
        for (long n = 0; n < 2_000; n++) {
            if (decider.decide(settlement(n), accepted, accepted.plusMinutes(1)).kind() == Kind.SUCCESS) {
                succeeded++;
            }
        }

        assertThat(succeeded).isBetween(1_500, 1_700); // 80% of 2000, give or take the hash's spread
    }

    @Test
    void aRateOfZeroAndOneHundredAreExact() {
        tenSecondDelay();
        properties.setChaosMode(ChaosMode.NORMAL);

        properties.setSuccessRate(100);
        for (long n = 0; n < 100; n++) {
            assertThat(decider.decide(settlement(n), accepted, accepted.plusMinutes(1)).kind()).isEqualTo(Kind.SUCCESS);
        }
        properties.setSuccessRate(0);
        for (long n = 0; n < 100; n++) {
            assertThat(decider.decide(settlement(n), accepted, accepted.plusMinutes(1)).kind()).isEqualTo(Kind.FAILURE);
        }
    }

    @Test
    void aSettlementAlwaysGetsTheSameAnswer() {
        properties.setChaosMode(ChaosMode.NORMAL);
        properties.setSuccessRate(50);
        UUID id = settlement(42);

        PayoutOutcomeDecider.Outcome first = decider.decide(id, accepted, accepted.plusHours(1));

        for (int i = 0; i < 5; i++) {
            assertThat(decider.decide(id, accepted, accepted.plusHours(1))).isEqualTo(first);
        }
    }

    @Test
    void theDelayStaysWithinTheConfiguredRange() {
        properties.setChaosMode(ChaosMode.SUCCESS);
        properties.setMinDelaySeconds(2);
        properties.setMaxDelaySeconds(12);

        for (long n = 0; n < 500; n++) {
            assertThat(decider.decide(settlement(n), accepted, accepted.plusSeconds(1)).kind()).isEqualTo(Kind.WAIT);
            assertThat(decider.decide(settlement(n), accepted, accepted.plusSeconds(12)).kind()).isEqualTo(Kind.SUCCESS);
        }
    }

    @Test
    void refusalFollowsTheRefuseRateAndIsNeverUsedInSuccessMode() {
        properties.setChaosMode(ChaosMode.NORMAL);
        properties.setRefuseRate(0);
        assertThat(decider.shouldRefuse(settlement(1))).isFalse();

        properties.setRefuseRate(100);
        assertThat(decider.shouldRefuse(settlement(1))).isTrue();

        properties.setChaosMode(ChaosMode.SUCCESS);
        assertThat(decider.shouldRefuse(settlement(1))).isFalse();
    }

    @Test
    void aPartialRefuseRateRefusesAboutThatShare() {
        properties.setChaosMode(ChaosMode.NORMAL);
        properties.setRefuseRate(10);

        int refused = 0;
        for (long n = 0; n < 2_000; n++) {
            if (decider.shouldRefuse(settlement(n))) {
                refused++;
            }
        }

        assertThat(refused).isBetween(120, 280);
    }
}
