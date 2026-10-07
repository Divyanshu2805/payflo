package com.project.payflo.vault_service.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** How many cards one merchant may tokenize in a short time (vault.velocity.* in config-repo/vault-service.yaml). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vault.velocity")
public class VelocityProperties {

    /** Off switch, for a test that tokenizes a lot. */
    private boolean enabled = true;

    /** The burst limit: a bot trying card numbers does it far faster than any checkout. */
    private int tokenizePerMinute = 30;

    /** The sustained limit, for a slow trickle of guesses. */
    private int tokenizePerHour = 600;
}
