package com.project.payflo.operations_service.settlement;

import com.project.payflo.common_lib.enums.ChaosMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How the simulated bank behaves when it is paid a payout (settlement.simulator.* in config-repo/operations-service.yaml).
 * The delay and the outcome of each transfer are derived from the settlement id, so a given settlement always gets
 * the same answer.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "settlement.simulator")
public class PayoutSimulatorProperties {

    /**
     * NORMAL: each transfer takes its delay and succeeds {@link #successRate} percent of the time. SLOW: delays
     * doubled. SUCCESS: every transfer succeeds. FAILURE: every transfer is declined. TIMEOUT: the bank never answers.
     */
    private ChaosMode chaosMode = ChaosMode.NORMAL;

    /** The bank answers a transfer between these many seconds after accepting it. */
    private int minDelaySeconds = 2;
    private int maxDelaySeconds = 12;

    /** Percent of transfers the bank completes (NORMAL and SLOW). */
    private int successRate = 95;

    /** Percent of transfers the bank refuses to accept at all (every mode but SUCCESS); the payout then fails at once. */
    private int refuseRate = 0;
}
