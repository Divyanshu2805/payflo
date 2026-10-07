package com.project.payflo.payment_service.simulator;

import com.project.payflo.common_lib.enums.ChaosMode;
import com.project.payflo.common_lib.enums.PaymentMethod;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "payment.simulator")
@Getter
@Setter
public class SimulatorConfig {

    private Integer pollIntervalMs = 2000;
    private ChaosMode chaosMode = ChaosMode.NORMAL;
    // Payments resolved per transaction (one lock query, batched writes and one commit for the whole batch).
    private Integer batchSize = 50;
    // Batches resolved at once. Each one holds a database connection, so keep this well under the pool size.
    private Integer concurrency = 4;
    private Map<String, MethodSimulatorConfig> methods = new HashMap<>();
    private Capture capture = new Capture();

    public MethodSimulatorConfig configFor(PaymentMethod method) {
        return methods.getOrDefault(method.name(), new MethodSimulatorConfig());
    }

    /** What the simulated acquirer does when asked to capture an authorized payment (see CaptureSimulator). */
    @Getter
    @Setter
    public static class Capture {
        // Percent of payments whose capture is refused, chosen from the payment's id so a given payment always
        // gets the same answer. The test values (a processor reference tagged CAPTURE_FAIL) refuse regardless.
        private Integer failureRate = 0;
        // How many captures of a payment that is to fail are refused before one is allowed through. 1 means the
        // automatic capture fails and the merchant's retry works; a large number never lets it through, so the
        // authorization lapses.
        private Integer failuresBeforeSuccess = 1;
    }

    @Getter
    @Setter
    public static  class MethodSimulatorConfig{
        private Integer minDelaySeconds = 1;
        private Integer maxDelaySeconds = 5;
        private Integer successRate = 80;
    }
}
