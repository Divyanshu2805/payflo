package com.project.payflo.api_gateway_service.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authenticates the platform operator for {@code /v1/admin/**}: one shared key in {@code X-Admin-Key}
 * ({@code app.security.admin-api-key}, from {@code ADMIN_API_KEY}). It is deliberately not a merchant credential and
 * not an API key row: nothing a merchant can create, rotate or leak opens the admin API, and a merchant's JWT or API
 * key is refused on these paths whatever it is worth elsewhere.
 *
 * <p>The key is compared as digests in constant time. Wrong guesses count against the caller's address in
 * {@link AuthFailureTracker}, like any failed authentication. With no key configured the admin API is off.
 */
@Component
public class AdminAuthHandler {

    public static final String ADMIN_KEY_HEADER = "X-Admin-Key";
    private static final String ADMIN_PREFIX = "/v1/admin/";

    private final byte[] expectedDigest;

    public AdminAuthHandler(SecurityRouteProperties properties) {
        String key = properties.getAdminApiKey();
        this.expectedDigest = key == null || key.isBlank() ? null : sha256(key);
    }

    public static boolean isAdminPath(String path) {
        return path != null && (path.startsWith(ADMIN_PREFIX) || path.equals("/v1/admin"));
    }

    public boolean isEnabled() {
        return expectedDigest != null;
    }

    /** Returns normally for the right key; throws {@link GatewayAuthenticationException} for a missing or wrong one. */
    public void authenticate(String presentedKey) {
        if (expectedDigest == null || presentedKey == null || presentedKey.isBlank()
                || !MessageDigest.isEqual(expectedDigest, sha256(presentedKey))) {
            throw new GatewayAuthenticationException("Invalid admin credentials");
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
