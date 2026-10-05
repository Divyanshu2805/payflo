package com.project.payflo.common_lib.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretConfigurationCheckerTest {

    private static final String DEFAULT_KEY = "arXfSlAXS4TRCw5tlKCFSwjG+D4C4ESUV47We3pw2eI=";
    private static final String DEFAULT_JWT = "dont-use-in-prod-a9asd7fulasjdfaklsdfu98q3uhjdiosh897as9d8f7ua9osufdjilasdjf";
    private static final String STRONG_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static SecretConfigurationChecker checker(boolean enforce, Map<String, String> properties) {
        return new SecretConfigurationChecker(properties::get, enforce);
    }

    @Test
    void flagsEveryCommittedDefault() {
        var problems = checker(false, Map.of(
                "jwt.secret-key", DEFAULT_JWT,
                "vault.master-key", DEFAULT_KEY,
                "webhook.secret-encryption-key", DEFAULT_KEY,
                "internal.api-token", "dev-internal-api-token-change-me")).problems();

        assertThat(problems).hasSize(4).allMatch(p -> p.contains("committed default"));
    }

    @Test
    void acceptsRealSecrets() {
        var strong = checker(true, Map.of(
                "jwt.secret-key", "a-long-random-value-that-nobody-has-committed-anywhere-0123456789",
                "vault.master-key", STRONG_KEY,
                "internal.api-token", "x".repeat(40)));

        assertThat(strong.problems()).isEmpty();
        assertThatCode(strong::check).doesNotThrowAnyException();
    }

    @Test
    void flagsKeysThatAreTheWrongSizeOrNotBase64() {
        var problems = checker(false, Map.of(
                "vault.master-key", Base64.getEncoder().encodeToString(new byte[16]),
                "webhook.secret-encryption-key", "not base64 at all!")).problems();

        assertThat(problems).hasSize(2);
    }

    @Test
    void flagsASecretThatIsTooShort() {
        assertThat(checker(false, Map.of("jwt.secret-key", "short")).problems()).hasSize(1);
        assertThat(checker(false, Map.of("internal.api-token", "short")).problems()).hasSize(1);
    }

    @Test
    void ignoresSecretsAServiceDoesNotHave() {
        // merchant-service has no vault.master-key, the gateway no webhook key
        assertThat(checker(true, Map.of()).problems()).isEmpty();
    }

    @Test
    void enforcementStopsStartupAndNamesTheProblemButNeverTheValue() {
        assertThatThrownBy(() -> checker(true, Map.of("vault.master-key", DEFAULT_KEY)).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vault.master-key")
                .hasMessageNotContaining(DEFAULT_KEY);
    }

    @Test
    void withoutEnforcementADefaultOnlyWarns() {
        assertThatCode(() -> checker(false, Map.of("vault.master-key", DEFAULT_KEY)).check()).doesNotThrowAnyException();
    }
}
