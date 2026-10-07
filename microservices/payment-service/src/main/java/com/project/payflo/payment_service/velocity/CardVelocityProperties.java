package com.project.payflo.payment_service.velocity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Card-testing protection on card payments (payment.velocity.card.* in config-repo/payment-service.yaml). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "payment.velocity.card")
public class CardVelocityProperties {

    /** Off switch, for a test that fails a lot of card payments on purpose. */
    private boolean enabled = true;

    /** How long failures and attempts are remembered; a merchant refused for card testing is let back in when it ends. */
    private int windowMinutes = 10;

    /** A merchant is only suspected after this many failed card payments in the window, however high the share. */
    private int minFailures = 20;

    /**
     * ...and when at least this share of its card payments in the window failed. A legitimate merchant's declines are a
     * small share of its card payments; someone running down a list of stolen numbers fails most of them.
     */
    private double failureRatio = 0.5;

    /** The most card payments one order takes, whatever happened to the earlier ones: the next one needs a new order. */
    private int maxAttemptsPerOrder = 5;
}
