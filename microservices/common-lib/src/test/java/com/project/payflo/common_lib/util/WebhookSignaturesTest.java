package com.project.payflo.common_lib.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignaturesTest {

    private static final String SECRET = "whsec_test";
    private static final String BODY = "{\"id\":\"evt_1\",\"event\":\"PAYMENT_STATUS_CHANGED\"}";
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final long T = NOW.getEpochSecond();

    private static boolean verify(String signature, String timestamp, String body) {
        return WebhookSignatures.verify(SECRET, signature, timestamp, body, 300, NOW);
    }

    @Test
    void aFreshCorrectlySignedRequestVerifies() {
        assertThat(verify(WebhookSignatures.sign(SECRET, T, BODY), String.valueOf(T), BODY)).isTrue();
    }

    @Test
    void theSignatureCoversTheTimestampSoAnOldRequestCannotBeReplayedWithAFreshOne() {
        String signatureFromAnHourAgo = WebhookSignatures.sign(SECRET, T - 3600, BODY);

        // replayed as is: the timestamp is outside the window
        assertThat(verify(signatureFromAnHourAgo, String.valueOf(T - 3600), BODY)).isFalse();
        // replayed with a refreshed timestamp: the signature no longer matches
        assertThat(verify(signatureFromAnHourAgo, String.valueOf(T), BODY)).isFalse();
    }

    @Test
    void theWindowIsInclusiveAndWorksInBothDirectionsForClockSkew() {
        assertThat(verify(WebhookSignatures.sign(SECRET, T - 300, BODY), String.valueOf(T - 300), BODY)).isTrue();
        assertThat(verify(WebhookSignatures.sign(SECRET, T - 301, BODY), String.valueOf(T - 301), BODY)).isFalse();
        assertThat(verify(WebhookSignatures.sign(SECRET, T + 300, BODY), String.valueOf(T + 300), BODY)).isTrue();
        assertThat(verify(WebhookSignatures.sign(SECRET, T + 301, BODY), String.valueOf(T + 301), BODY)).isFalse();
    }

    @Test
    void aChangedBodyOrAWrongSecretFails() {
        String signature = WebhookSignatures.sign(SECRET, T, BODY);

        assertThat(verify(signature, String.valueOf(T), BODY + " ")).isFalse();
        assertThat(WebhookSignatures.verify("other-secret", signature, String.valueOf(T), BODY, 300, NOW)).isFalse();
    }

    @Test
    void theSignatureIsHexAndCaseInsensitiveOnTheWire() {
        String signature = WebhookSignatures.sign(SECRET, T, BODY);

        assertThat(signature).matches("[0-9a-f]{64}");
        assertThat(verify(signature.toUpperCase(), String.valueOf(T), BODY)).isTrue();
    }

    @Test
    void missingOrMalformedHeadersAreSimplyRejected() {
        String signature = WebhookSignatures.sign(SECRET, T, BODY);

        assertThat(verify(null, String.valueOf(T), BODY)).isFalse();
        assertThat(verify(signature, null, BODY)).isFalse();
        assertThat(verify(signature, "not-a-number", BODY)).isFalse();
        assertThat(verify(signature, "", BODY)).isFalse();
        assertThat(verify("", String.valueOf(T), BODY)).isFalse();
        assertThat(WebhookSignatures.verify(null, signature, String.valueOf(T), BODY, 300, NOW)).isFalse();
    }

    @Test
    void theTimestampIsPartOfTheSignedContentNotJustAHeader() {
        // "<timestamp>.<body>": moving a digit between the two must not produce the same signature
        assertThat(WebhookSignatures.sign(SECRET, 12, "3.body")).isNotEqualTo(WebhookSignatures.sign(SECRET, 123, "body"));
    }
}
