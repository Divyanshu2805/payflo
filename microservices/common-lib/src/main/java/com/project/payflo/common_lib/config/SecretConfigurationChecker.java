package com.project.payflo.common_lib.config;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Checks, at start-up, that the secrets a service is using aren't the development defaults committed to
 * this repository (or too weak to be a secret). The defaults exist so the stack starts with no setup, but
 * in a shared environment they are public knowledge: anyone could forge a JWT, decrypt vaulted cards or
 * call the internal API.
 *
 * <p>With {@code app.security.enforce-strong-secrets=true} a bad secret stops the service from starting.
 * Otherwise each one is logged as a warning, so a developer sees it without being blocked. Only secrets a
 * service actually has are checked — vault-service has the master key, merchant-service doesn't.
 */
@Slf4j
public class SecretConfigurationChecker {

    // Every default committed in config-repo, secrets.env.example or the monolith.
    private static final Set<String> KNOWN_DEFAULTS = Set.of(
            "dont-use-in-prod-a9asd7fulasjdfaklsdfu98q3uhjdiosh897as9d8f7ua9osufdjilasdjf",
            "change-me-dev-only-jwt-secret-at-least-64-characters-long-0123456789abcdef",
            "arXfSlAXS4TRCw5tlKCFSwjG+D4C4ESUV47We3pw2eI=",
            "dev-internal-api-token-change-me",
            "change-me-generate-with-openssl-rand-base64-32");

    private final Function<String, String> properties;
    private final boolean enforce;

    public SecretConfigurationChecker(Function<String, String> properties, boolean enforce) {
        this.properties = properties;
        this.enforce = enforce;
    }

    /** Called by Spring after construction. */
    public void check() {
        List<String> problems = problems();
        if (problems.isEmpty()) {
            return;
        }
        String summary = String.join("; ", problems);
        if (enforce) {
            throw new IllegalStateException("Refusing to start with weak or default secrets: " + summary);
        }
        log.warn("Development secrets in use ({}). Fine locally; set real values, and "
                + "app.security.enforce-strong-secrets=true, in any shared environment.", summary);
    }

    List<String> problems() {
        List<String> problems = new ArrayList<>();
        checkText("jwt.secret-key", 32, problems);
        checkBase64Key("vault.master-key", problems);
        checkBase64Key("webhook.secret-encryption-key", problems);
        checkText("internal.api-token", 24, problems);
        return problems;
    }

    private void checkText(String name, int minLength, List<String> problems) {
        String value = properties.apply(name);
        if (value == null || value.isBlank()) return;
        if (KNOWN_DEFAULTS.contains(value)) {
            problems.add(name + " is a committed default");
        } else if (value.length() < minLength) {
            problems.add(name + " is shorter than " + minLength + " characters");
        }
    }

    private void checkBase64Key(String name, List<String> problems) {
        String value = properties.apply(name);
        if (value == null || value.isBlank()) return;
        if (KNOWN_DEFAULTS.contains(value)) {
            problems.add(name + " is a committed default");
            return;
        }
        try {
            if (Base64.getDecoder().decode(value).length != 32) {
                problems.add(name + " is not a 256-bit (32 byte) base64 key");
            }
        } catch (IllegalArgumentException e) {
            problems.add(name + " is not valid base64");
        }
    }
}
