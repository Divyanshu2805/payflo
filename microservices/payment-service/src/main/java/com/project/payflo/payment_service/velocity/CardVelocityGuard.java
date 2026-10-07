package com.project.payflo.payment_service.velocity;

import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.VelocityLimitException;
import com.project.payflo.common_lib.ratelimit.VelocityCounters;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Card-testing protection on payments. Someone with a merchant's API key runs a list of stolen card numbers through
 * it, one small payment each, to learn which are live. Two rules stop that, neither of which bothers an honest merchant:
 *
 * <ul>
 *   <li><b>A merchant whose card payments mostly fail is refused for a while.</b> In a window of recent card payments,
 *       once at least {@code min-failures} have failed <i>and</i> at least {@code failure-ratio} of them did, further card
 *       payments are refused with {@code 429 CARD_TESTING_SUSPECTED} until the window ends. Declines are a small share of
 *       a real merchant's payments (the load test's simulated bank declines about one in ten); a bot fails nearly all.</li>
 *   <li><b>An order takes at most {@code max-attempts-per-order} card payments</b> ({@code 400 ORDER_CARD_ATTEMPTS_EXCEEDED}),
 *       so one order can't be used as a card-guessing tool: the next attempt needs a new order.</li>
 * </ul>
 *
 * A failure is counted when the acquirer or bank refused the card — not when PayFlo itself couldn't reach the vault, which
 * is not the card's fault. Attempts and failures are in one Redis hash so both cover the same window. If Redis can't be
 * reached nothing is counted or refused.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CardVelocityGuard {

    static final String KEY = "velocity:card:";
    static final String ATTEMPTS = "attempts";
    static final String FAILURES = "failures";

    /** vault-service's code for "the charge couldn't be done" (an outage, not a decline): not the card's fault. */
    static final String VAULT_FAILURE_CODE = "VAULT_CHARGE_FAILED";

    private final VelocityCounters counters;
    private final CardVelocityProperties properties;
    private final MeterRegistry meterRegistry;

    /** Refuses a merchant that looks like it is testing card numbers. Called before a card payment is recorded. */
    public void requireAllowed(UUID merchantId) {
        if (!properties.isEnabled()) {
            return;
        }
        Map<String, Long> counts = counters.counts(KEY + merchantId);
        long failures = counts.getOrDefault(FAILURES, 0L);
        long attempts = counts.getOrDefault(ATTEMPTS, 0L);
        if (failures >= properties.getMinFailures() && failures >= properties.getFailureRatio() * attempts) {
            meterRegistry.counter("payflo.velocity.refused", "rule", "card-testing").increment();
            log.warn("Card payments refused for merchant {}: {} of {} card payments failed in the window",
                    merchantId, failures, attempts);
            throw new VelocityLimitException("CARD_TESTING_SUSPECTED",
                    "Too many of this merchant's card payments have been declined in a short time. Try again later",
                    counters.secondsLeft(KEY + merchantId));
        }
    }

    /** A card payment was recorded. */
    public void recordAttempt(UUID merchantId) {
        if (properties.isEnabled()) {
            counters.increment(KEY + merchantId, ATTEMPTS, window());
        }
    }

    /** The card was refused by the acquirer or the bank; {@code errorCode} tells that apart from an outage. */
    public void recordDecline(UUID merchantId, String errorCode) {
        if (properties.isEnabled() && !VAULT_FAILURE_CODE.equals(errorCode)) {
            counters.increment(KEY + merchantId, FAILURES, window());
        }
    }

    /** An order that has already taken as many card payments as it is allowed takes no more. */
    public void requireOrderAttemptsBelowLimit(long cardPaymentsSoFar) {
        if (properties.isEnabled() && cardPaymentsSoFar >= properties.getMaxAttemptsPerOrder()) {
            meterRegistry.counter("payflo.velocity.refused", "rule", "order-card-attempts").increment();
            throw new BusinessRuleViolationException("ORDER_CARD_ATTEMPTS_EXCEEDED",
                    "This order has had " + properties.getMaxAttemptsPerOrder() + " card payments. Create a new order to try again");
        }
    }

    private Duration window() {
        return Duration.ofMinutes(properties.getWindowMinutes());
    }
}
