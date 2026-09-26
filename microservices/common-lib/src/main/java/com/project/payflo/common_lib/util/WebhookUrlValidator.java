package com.project.payflo.common_lib.util;

import com.project.payflo.common_lib.exception.BusinessRuleViolationException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Decides whether a merchant-supplied webhook URL may be called from inside the platform.
 *
 * <p>Without this, a merchant could point a webhook at {@code localhost}, a cluster Service such as
 * {@code http://vault-service/internal/...} or a cloud metadata address, and operations-service would call
 * it. Every address the host resolves to is checked, since a name can resolve to several.
 *
 * <p>Unspecified, link-local (which includes 169.254.169.254) and multicast addresses are always refused.
 * Loopback, private-range and carrier-grade-NAT addresses, and plain {@code http}, are refused unless
 * {@code allowPrivateTargets} is set — a development setting, so a webhook can point at a local server.
 */
public class WebhookUrlValidator {

    public static final String ERROR_CODE = "WEBHOOK_URL_NOT_ALLOWED";

    private final boolean allowPrivateTargets;

    public WebhookUrlValidator(boolean allowPrivateTargets) {
        this.allowPrivateTargets = allowPrivateTargets;
    }

    /** @throws BusinessRuleViolationException if the URL must not be called */
    public void validate(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            throw reject("Webhook URL is not a valid URL");
        }

        String scheme = uri.getScheme();
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean http = "http".equalsIgnoreCase(scheme);
        if (!https && !(http && allowPrivateTargets)) {
            throw reject("Webhook URL must use https");
        }

        if (uri.getRawUserInfo() != null) {
            throw reject("Webhook URL must not contain credentials");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw reject("Webhook URL must have a host");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw reject("Webhook URL host could not be resolved");
        }

        for (InetAddress address : addresses) {
            if (isAlwaysBlocked(address) || (!allowPrivateTargets && isPrivate(address))) {
                throw reject("Webhook URL must point to a public address");
            }
        }
    }

    private static boolean isAlwaysBlocked(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress();
    }

    private static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0                                   // 0.0.0.0/8
                    || (first == 100 && (second & 0xc0) == 64)  // 100.64.0.0/10, carrier-grade NAT
                    || (first == 198 && (second & 0xfe) == 18); // 198.18.0.0/15, benchmarking
        }
        return (b[0] & 0xfe) == 0xfc; // fc00::/7, IPv6 unique local
    }

    private static BusinessRuleViolationException reject(String message) {
        return new BusinessRuleViolationException(ERROR_CODE, message);
    }
}
