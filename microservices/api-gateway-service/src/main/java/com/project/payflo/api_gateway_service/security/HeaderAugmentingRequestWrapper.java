package com.project.payflo.api_gateway_service.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The request as the downstream service sees it: the headers the client sent, minus every identity
 * header, plus the ones the gateway sets after authenticating.
 *
 * <p>Services believe {@code X-Merchant-Id} and friends because only the gateway sets them. If a client's
 * copy got through — on a public route, or any header the gateway doesn't overwrite for this credential
 * type — it would be trusted as if the gateway had written it. So they are always dropped here.
 */
public class HeaderAugmentingRequestWrapper extends HttpServletRequestWrapper {

    /** Headers only the gateway may set (compared case-insensitively). */
    static final Set<String> IDENTITY_HEADERS = Set.of(
            "x-merchant-id", "x-key-id", "x-user-role", "x-user-email", "x-environment",
            // set only after the admin key is checked, and the caller's address as the gateway saw it
            "x-platform-admin", "x-client-ip");

    private final Map<String, String> extraHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public HeaderAugmentingRequestWrapper(HttpServletRequest request) {
        super(request);
    }

    @Override
    public String getAuthType() {
        return super.getAuthType();
    }

    public void putHeader(String name, String value) {
        extraHeaders.put(name, value);
    }

    private static boolean isClientIdentityHeader(String name) {
        return name != null && IDENTITY_HEADERS.contains(name.toLowerCase());
    }

    @Override
    public String getHeader(String name) {
        String value = extraHeaders.get(name);
        if (value != null) return value;
        return isClientIdentityHeader(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        String value = extraHeaders.get(name);
        if (value != null) return Collections.enumeration(Collections.singletonList(value));
        return isClientIdentityHeader(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        // Case-insensitive, like HTTP header names, so a gateway-set header replaces a client's of any case.
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(extraHeaders.keySet());
        Collections.list(super.getHeaderNames()).stream()
                .filter(name -> !isClientIdentityHeader(name))
                .forEach(names::add);
        return Collections.enumeration(names);
    }
}
