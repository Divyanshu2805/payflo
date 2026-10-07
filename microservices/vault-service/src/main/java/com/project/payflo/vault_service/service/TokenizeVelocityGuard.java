package com.project.payflo.vault_service.service;

import com.project.payflo.common_lib.exception.VelocityLimitException;
import com.project.payflo.common_lib.ratelimit.VelocityCounters;
import com.project.payflo.vault_service.config.VelocityProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Card testing starts here: someone with a merchant's API key feeds stolen card numbers to the tokenizer, to find which
 * are live by what happens when they are charged. No checkout tokenizes dozens of cards a minute, so a merchant that does
 * is refused for the rest of the window, however valid its key.
 *
 * <p>Two fixed windows per merchant — a burst limit and a sustained one. Refused attempts count too, so hammering the
 * endpoint doesn't help; the window itself ends on its own. If Redis can't be reached nothing is refused.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenizeVelocityGuard {

    static final String MINUTE_KEY = "velocity:tokenize:minute:";
    static final String HOUR_KEY = "velocity:tokenize:hour:";
    private static final String FIELD = "tokenized";

    private final VelocityCounters counters;
    private final VelocityProperties properties;
    private final MeterRegistry meterRegistry;

    public void requireAllowed(UUID merchantId) {
        if (!properties.isEnabled() || merchantId == null) {
            return;
        }
        refuseIfOver(MINUTE_KEY + merchantId, Duration.ofMinutes(1), properties.getTokenizePerMinute(), "tokenize-per-minute");
        refuseIfOver(HOUR_KEY + merchantId, Duration.ofHours(1), properties.getTokenizePerHour(), "tokenize-per-hour");
    }

    private void refuseIfOver(String key, Duration window, int limit, String rule) {
        long count = counters.increment(key, FIELD, window);
        if (count > limit) {
            long retryAfter = counters.secondsLeft(key);
            meterRegistry.counter("payflo.velocity.refused", "rule", rule).increment();
            log.warn("Tokenization refused ({}): {} cards in the window, limit {}", rule, count, limit);
            throw new VelocityLimitException("CARD_TOKENIZATION_LIMIT_EXCEEDED",
                    "Too many cards were tokenized in a short time. Try again later", retryAfter);
        }
    }
}
