package com.project.payflo.common_lib.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * How a webhook delivery is signed, and how a receiver checks it.
 *
 * <p>Every attempt carries {@value #TIMESTAMP_HEADER} (seconds since the epoch, taken when that attempt is sent)
 * and a signature, HMAC-SHA256 in hex with the endpoint's secret, over {@code <timestamp>.<raw body>}. The
 * timestamp is part of what is signed, so a captured request can't be replayed later with a fresher one, and a
 * receiver that rejects timestamps older than its tolerance (default 5 minutes) rejects a replay. A retry is a new
 * attempt: same body, new timestamp, new signature.
 *
 * <p>This class is the reference for receivers; the delivery code and its tests use it too.
 */
public final class WebhookSignatures {

    public static final String TIMESTAMP_HEADER = "X-PayFlo-Timestamp";

    /** How far a timestamp may be from the receiver's clock, either way. */
    public static final long DEFAULT_TOLERANCE_SECONDS = 300;

    private static final SignerUtil SIGNER = new SignerUtil();

    private WebhookSignatures() {
    }

    /** The signature for an attempt sent at {@code timestamp} (epoch seconds) with this exact body. */
    public static String sign(String secret, long timestamp, String rawBody) {
        return SIGNER.sign(timestamp + "." + rawBody, secret);
    }

    /**
     * Whether a request is genuine and fresh. The body must be the raw bytes received, not a re-serialization.
     * Compared in constant time; a missing or malformed header is simply {@code false}.
     */
    public static boolean verify(String secret, String signatureHeader, String timestampHeader, String rawBody,
                                 long toleranceSeconds, Instant now) {
        if (secret == null || signatureHeader == null || timestampHeader == null || rawBody == null) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException malformed) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - timestamp) > toleranceSeconds) {
            return false;
        }
        byte[] expected = sign(secret, timestamp, rawBody).getBytes(StandardCharsets.UTF_8);
        byte[] presented = signatureHeader.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, presented);
    }
}
