package com.project.payflo.operations_service.settlement;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// Settlement settings (settlement.* in config-repo/operations-service.yaml); the defaults are used when unset.
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "settlement")
public class SettlementProperties {

    /** The platform fee, as a share of what the merchant took after refunds. */
    private double feeRate = 0.02;

    /** GST charged on the fee. */
    private double gstRate = 0.18;

    /** A payment is paid out only once it is this many days old (T+N). 0 pays out everything captured. */
    private int holdDays = 0;

    /** Merchants settled at the same time. Each holds database connections and calls three services. */
    private int concurrency = 4;

    /** The most payments paid out to one merchant in one run; the rest wait for the next. */
    private int maxPaymentsPerRun = 20_000;

    /** How many payments are fetched from payment-service at a time. */
    private int pageSize = 1_000;

    /**
     * A transfer the bank accepted but never answered is failed after this long, so its payments become payable
     * again. (A real integration would ask the bank for the transfer's status by its reference first.)
     */
    private int transferTimeoutMinutes = 120;
}
