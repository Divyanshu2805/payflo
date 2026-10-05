package com.project.payflo.common_lib.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Requires a shared token on every {@code /internal/**} request. Those endpoints take any merchant id
 * and decide nothing for themselves, so until now the only thing protecting them was that nobody else
 * could reach the service. With the token, a pod that can reach a service on the network still can't
 * call them without the secret the services share.
 */
public class InternalApiAuthFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Internal-Token";
    private static final String INTERNAL_PREFIX = "/internal/";

    private final byte[] expectedDigest;

    public InternalApiAuthFilter(String token) {
        if (token == null || token.isBlank()) {
            // An empty token would accept an empty header: refuse to run rather than guard nothing.
            throw new IllegalStateException("internal.api-token (INTERNAL_API_TOKEN) must not be empty");
        }
        this.expectedDigest = sha256(token);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String presented = request.getHeader(TOKEN_HEADER);
        // Compared as digests, in constant time, so the check leaks nothing about the token.
        if (presented != null && MessageDigest.isEqual(expectedDigest, sha256(presented))) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"errorCode\":\"UNAUTHORIZED\",\"errorDescription\":\"Internal API requires a service token\"}");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
