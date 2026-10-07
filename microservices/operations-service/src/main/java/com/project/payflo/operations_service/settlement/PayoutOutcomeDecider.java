package com.project.payflo.operations_service.settlement;

import com.project.payflo.common_lib.enums.ChaosMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What the simulated bank does with a payout: wait, pay it, or decline it. Pure: the answer depends only on the
 * settings, the settlement id and the clock, which is what makes it testable and repeatable.
 */
@Component
@RequiredArgsConstructor
public class PayoutOutcomeDecider {

    static final String DECLINED_CODE = "SIM_PAYOUT_DECLINED";
    static final String DECLINED_DESCRIPTION = "Simulated bank declined the transfer";

    public enum Kind { WAIT, SUCCESS, FAILURE }

    public record Outcome(Kind kind, String errorCode, String errorDescription) {
        static final Outcome WAIT = new Outcome(Kind.WAIT, null, null);
        static final Outcome SUCCESS = new Outcome(Kind.SUCCESS, null, null);
        static final Outcome FAILURE = new Outcome(Kind.FAILURE, DECLINED_CODE, DECLINED_DESCRIPTION);
    }

    private final PayoutSimulatorProperties properties;

    /** The bank's answer to a transfer it accepted at {@code transferStartedAt}, as of {@code now}. */
    public Outcome decide(UUID settlementId, LocalDateTime transferStartedAt, LocalDateTime now) {
        ChaosMode mode = properties.getChaosMode();
        if (mode == ChaosMode.TIMEOUT) {
            return Outcome.WAIT;
        }
        if (now.isBefore(transferStartedAt.plusSeconds(delaySeconds(settlementId, mode)))) {
            return Outcome.WAIT;
        }
        return switch (mode) {
            case SUCCESS -> Outcome.SUCCESS;
            case FAILURE -> Outcome.FAILURE;
            default -> bucket(settlementId.hashCode()) < properties.getSuccessRate() ? Outcome.SUCCESS : Outcome.FAILURE;
        };
    }

    /** Whether the bank refuses to accept this transfer at all. */
    public boolean shouldRefuse(UUID settlementId) {
        if (properties.getChaosMode() == ChaosMode.SUCCESS || properties.getRefuseRate() <= 0) {
            return false;
        }
        // A different slice of the id than the outcome uses, so the two draws are independent
        return bucket(Long.hashCode(settlementId.getLeastSignificantBits() * 31 + 17)) < properties.getRefuseRate();
    }

    private int delaySeconds(UUID settlementId, ChaosMode mode) {
        int min = properties.getMinDelaySeconds();
        int range = Math.max(0, properties.getMaxDelaySeconds() - min);
        int delay = min + Math.floorMod(settlementId.hashCode(), range + 1);
        return mode == ChaosMode.SLOW ? delay * 2 : delay;
    }

    private static int bucket(int hash) {
        return Math.floorMod(hash, 100);
    }
}
