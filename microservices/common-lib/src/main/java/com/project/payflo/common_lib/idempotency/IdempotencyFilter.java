package com.project.payflo.common_lib.idempotency;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.exception.IdempotencyConflictException;
import com.project.payflo.common_lib.exception.IdempotencyKeyReusedException;
import com.project.payflo.common_lib.exception.IdempotencyResponseUnavailableException;
import com.project.payflo.common_lib.web.CachedBodyHttpServletRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Makes a write request safe to retry. A request carrying {@code X-Idempotency-Key} runs once; a repeat of the same
 * request gets the first response back.
 *
 * <p>The key is bound to the request: each guarded request is fingerprinted (SHA-256 of method, path, query and body),
 * and the same key arriving with a different fingerprint is refused with {@code 422 IDEMPOTENCY_KEY_REUSED}, instead
 * of silently answering a different question with the first answer. That holds while the first request is still in
 * flight too.
 *
 * <p>Two kinds of path are treated specially:
 * <ul>
 *   <li><b>body-excluded</b> paths carry card data or passwords in the body. Their fingerprint ignores the body: a
 *       hash of a card number or a password is small enough to guess offline, so it must not be kept in Redis.</li>
 *   <li><b>unreplayable</b> paths return a secret that is shown once (API keys, webhook secrets). Their response is
 *       never stored, only a marker, so a retry gets {@code 409 IDEMPOTENT_RESPONSE_NOT_REPLAYABLE} rather than the
 *       secret being kept in Redis for 24 hours.</li>
 * </ul>
 */
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Set<String> GUARDED_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(30);
    private static final Duration COMPLETED_TTL = Duration.ofHours(24);
    private static final String SEPARATOR = "|";
    private static final String NOT_REPLAYABLE = "NR";
    // A request body larger than this is not fingerprinted, and so not guarded: JSON requests here are small.
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int FINGERPRINT_LENGTH = 64; // SHA-256, hex

    private final MerchantContext merchantContext;
    private final IdempotencyStore idempotencyStore;
    private final HandlerExceptionResolver handlerExceptionResolver;
    private final List<String> unreplayablePaths;
    private final List<String> bodyExcludedPaths;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public IdempotencyFilter(MerchantContext merchantContext, IdempotencyStore idempotencyStore,
                             HandlerExceptionResolver handlerExceptionResolver) {
        this(merchantContext, idempotencyStore, handlerExceptionResolver, List.of(), List.of());
    }

    public IdempotencyFilter(MerchantContext merchantContext, IdempotencyStore idempotencyStore,
                             HandlerExceptionResolver handlerExceptionResolver,
                             List<String> unreplayablePaths, List<String> bodyExcludedPaths) {
        this.merchantContext = merchantContext;
        this.idempotencyStore = idempotencyStore;
        this.handlerExceptionResolver = handlerExceptionResolver;
        this.unreplayablePaths = unreplayablePaths;
        this.bodyExcludedPaths = bodyExcludedPaths;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (!GUARDED_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String rawKey = request.getHeader("X-Idempotency-Key");
        if (rawKey == null || rawKey.isBlank()) { // No idem-key found, continue
            chain.doFilter(request, response);
            return;
        }

        // A stored response is only ever replayed to the merchant it was created for. Without an
        // authenticated merchant (the gateway, which never trusts inbound identity headers, and the
        // public signup/login routes) a key would be shared by every caller, so don't cache at all.
        UUID merchantId = merchantContext.getMerchantId();
        if (merchantId == null) {
            chain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();
        HttpServletRequest effective = request;
        String fingerprint;
        if (matches(bodyExcludedPaths, uri)) {
            fingerprint = fingerprint(request, new byte[0]);
        } else {
            if (request.getContentLengthLong() > MAX_BODY_BYTES) {
                log.warn("IdempotencyFilter: body too large to fingerprint, key not honoured for {} {}", request.getMethod(), uri);
                chain.doFilter(request, response);
                return;
            }
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) { // chunked and too large: pass it on whole, without the guard
                chain.doFilter(new CachedBodyHttpServletRequest(request, body, request.getInputStream()), response);
                return;
            }
            effective = new CachedBodyHttpServletRequest(request, body, null);
            fingerprint = fingerprint(request, body);
        }

        // Method and path are part of the key so a key reused on another endpoint can't replay
        // this endpoint's response.
        String key = merchantId + ":" + request.getMethod() + ":" + uri + ":" + rawKey;

        boolean claimed = idempotencyStore.setIfAbsent(key, IdempotencyStore.IN_PROGRESS + SEPARATOR + fingerprint, IN_PROGRESS_TTL);

        if (!claimed) {
            // another request has already claimed this key
            Optional<String> existing = idempotencyStore.get(key);

            if (existing.isEmpty()) {
                reject(request, response, new IdempotencyConflictException("A request with this idempotency key is in progress"));
            } else if (existing.get().startsWith(IdempotencyStore.IN_PROGRESS)) {
                // in progress: by the same request, or by a different one that reused the key
                String owner = fingerprintOf(existing.get());
                if (owner != null && !owner.equals(fingerprint)) {
                    reject(request, response, reused());
                } else {
                    reject(request, response, new IdempotencyConflictException("A request with this idempotency key is in progress"));
                }
            } else {
                replay(request, response, existing.get(), fingerprint);
            }
            return;
        }

        // first time claim
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(effective, wrapper);
        } finally {
            int status = wrapper.getStatus();
            byte[] bodyBytes = wrapper.getContentAsByteArray();

            if (status < 400 && matches(unreplayablePaths, uri)) {
                // Succeeded, but the response holds a secret shown once: remember that it ran, never what it said
                idempotencyStore.store(key, fingerprint + SEPARATOR + NOT_REPLAYABLE + SEPARATOR, COMPLETED_TTL);
                log.debug("IdempotencyFilter: stored a no-replay marker for key={}", key);
            } else if (status < 400 && bodyBytes.length > 0) {
                // Success — store the completed response for future replays
                String stored = fingerprint + SEPARATOR + status + SEPARATOR + new String(bodyBytes, StandardCharsets.UTF_8);
                idempotencyStore.store(key, stored, COMPLETED_TTL);
                log.debug("IdempotencyFilter: stored response status={} key={}", status, key);
            } else {
                // Error or empty — delete placeholder so client can retry cleanly
                idempotencyStore.delete(key);
                log.debug("IdempotencyFilter: deleted placeholder after error status={} key={}", status, key);
            }
            // Always flush buffered body to the actual response.
            // If this is skipped the client receives an empty body.
            wrapper.copyBodyToResponse();
        }
    }

    // A completed value is "fingerprint|status|body"; one written before request fingerprints existed is
    // "status|body" and is replayed as before (it expires within a day).
    private void replay(HttpServletRequest request, HttpServletResponse response, String stored, String fingerprint)
            throws IOException {
        int first = stored.indexOf(SEPARATOR);
        if (first < 0) {
            reject(request, response, new IdempotencyConflictException("A request with this idempotency key is in progress"));
            return;
        }

        String rest = stored;
        if (first == FINGERPRINT_LENGTH) {
            if (!stored.substring(0, first).equals(fingerprint)) {
                reject(request, response, reused());
                return;
            }
            rest = stored.substring(first + 1);
        }

        int second = rest.indexOf(SEPARATOR);
        if (second < 0) {
            reject(request, response, new IdempotencyConflictException("A request with this idempotency key is in progress"));
            return;
        }
        String statusToken = rest.substring(0, second);
        if (NOT_REPLAYABLE.equals(statusToken)) {
            reject(request, response, new IdempotencyResponseUnavailableException(
                    "This request already succeeded, but its response contains a secret that is shown only once and can't be "
                            + "replayed. Use a new idempotency key to create another."));
            return;
        }

        int status = Integer.parseInt(statusToken);
        String body = rest.substring(second + 1);

        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, RuntimeException ex) {
        handlerExceptionResolver.resolveException(request, response, null, ex);
    }

    private static IdempotencyKeyReusedException reused() {
        return new IdempotencyKeyReusedException(
                "This idempotency key was already used for a different request. Use a new key for a new request.");
    }

    // The fingerprint inside an in-progress placeholder ("__IN_PROGRESS__|<fingerprint>"), or null for an old one.
    private static String fingerprintOf(String placeholder) {
        int separator = placeholder.indexOf(SEPARATOR);
        return separator < 0 ? null : placeholder.substring(separator + 1);
    }

    private boolean matches(List<String> patterns, String uri) {
        for (String pattern : patterns) {
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    private static String fingerprint(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.getMethod().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            String query = request.getQueryString();
            if (query != null) {
                digest.update(query.getBytes(StandardCharsets.UTF_8));
            }
            digest.update((byte) 0);
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
