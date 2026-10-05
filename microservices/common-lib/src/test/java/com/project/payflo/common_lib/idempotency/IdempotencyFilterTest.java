package com.project.payflo.common_lib.idempotency;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.exception.IdempotencyConflictException;
import com.project.payflo.common_lib.exception.IdempotencyKeyReusedException;
import com.project.payflo.common_lib.exception.IdempotencyResponseUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class IdempotencyFilterTest {

    private final Map<String, String> redis = new HashMap<>();
    private final IdempotencyStore store = new IdempotencyStore() {
        @Override public boolean setIfAbsent(String key, String placeholder, Duration ttl) { return redis.putIfAbsent(key, placeholder) == null; }
        @Override public void store(String key, String value, Duration ttl) { redis.put(key, value); }
        @Override public Optional<String> get(String key) { return Optional.ofNullable(redis.get(key)); }
        @Override public void delete(String key) { redis.remove(key); }
    };

    private final MerchantContext context = new MerchantContext();
    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final IdempotencyFilter filter = new IdempotencyFilter(context, store, resolver,
            List.of("/v1/merchants/api-keys"), List.of("/v1/vault/**"));
    private final AtomicInteger handlerCalls = new AtomicInteger();

    // A handler that answers 201 with a body naming the call it was.
    private final FilterChain handler = (req, res) -> {
        res.setContentType("application/json");
        res.getWriter().write("{\"call\":" + handlerCalls.incrementAndGet() + "}");
        ((HttpServletResponse) res).setStatus(201);
    };

    @BeforeEach
    void authenticated() {
        context.setMerchantId(UUID.randomUUID());
    }

    private MockHttpServletRequest request(String path, String key, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader("X-Idempotency-Key", key);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private MockHttpServletResponse post(String path, String key, String body) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(path, key, body), response, handler);
        return response;
    }

    private MockHttpServletResponse post(String path, String key) throws Exception {
        return post(path, key, "{\"orderId\":\"o-1\"}");
    }

    private RuntimeException rejection() {
        ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
        verify(resolver).resolveException(any(), any(), any(), captor.capture());
        return (RuntimeException) captor.getValue();
    }

    @Test
    void aRetryReplaysTheFirstResponseWithoutRunningTheHandlerAgain() throws Exception {
        MockHttpServletResponse first = post("/v1/payments", "k1");
        MockHttpServletResponse retry = post("/v1/payments", "k1");

        assertThat(handlerCalls).hasValue(1);
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(retry.getContentAsString()).isEqualTo(first.getContentAsString());
    }

    @Test
    void theSameKeyOnAnotherEndpointIsNotAReplay() throws Exception {
        post("/v1/payments", "k1");
        MockHttpServletResponse other = post("/v1/orders", "k1");

        assertThat(handlerCalls).hasValue(2);
        assertThat(other.getContentAsString()).isEqualTo("{\"call\":2}");
    }

    @Test
    void theSameKeyFromAnotherMerchantIsNotAReplay() throws Exception {
        post("/v1/payments", "k1");
        context.setMerchantId(UUID.randomUUID());
        MockHttpServletResponse other = post("/v1/payments", "k1");

        assertThat(handlerCalls).hasValue(2);
        assertThat(other.getContentAsString()).isEqualTo("{\"call\":2}");
    }

    @Test
    void withoutAMerchantNothingIsCachedOrReplayed() throws Exception {
        MerchantContext anonymous = new MerchantContext(); // the gateway, or a public route
        IdempotencyFilter anonymousFilter = new IdempotencyFilter(anonymous, store, resolver);

        for (int i = 0; i < 2; i++) {
            anonymousFilter.doFilter(request("/v1/auth/login", "shared", "{}"), new MockHttpServletResponse(), handler);
        }

        assertThat(handlerCalls).hasValue(2);
        assertThat(redis).isEmpty();
    }

    @Test
    void aFailedFirstRequestReleasesTheKey() throws Exception {
        FilterChain failing = (req, res) -> ((HttpServletResponse) res).setStatus(500);
        filter.doFilter(request("/v1/payments", "k1", "{}"), new MockHttpServletResponse(), failing);

        assertThat(post("/v1/payments", "k1", "{}").getStatus()).isEqualTo(201);
        assertThat(handlerCalls).hasValue(1);
    }

    @Test
    void theSameKeyWithADifferentBodyIsRefusedNotReplayed() throws Exception {
        post("/v1/payments", "k1", "{\"orderId\":\"o-1\"}");
        MockHttpServletResponse other = post("/v1/payments", "k1", "{\"orderId\":\"o-2\"}");

        assertThat(handlerCalls).hasValue(1);
        assertThat(other.getContentAsString()).isEmpty();
        assertThat(rejection()).isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void theSameKeyWithADifferentQueryStringIsRefused() throws Exception {
        post("/v1/payments", "k1");
        MockHttpServletRequest withQuery = request("/v1/payments", "k1", "{\"orderId\":\"o-1\"}");
        withQuery.setQueryString("dryRun=true");
        filter.doFilter(withQuery, new MockHttpServletResponse(), handler);

        assertThat(handlerCalls).hasValue(1);
        assertThat(rejection()).isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void theHandlerCanStillReadTheBodyAfterItWasFingerprinted() throws Exception {
        String[] seen = new String[1];
        FilterChain reading = (req, res) -> {
            seen[0] = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            ((HttpServletResponse) res).setStatus(201);
            res.getWriter().write("{}");
        };

        filter.doFilter(request("/v1/payments", "k1", "{\"orderId\":\"o-1\"}"), new MockHttpServletResponse(), reading);

        assertThat(seen[0]).isEqualTo("{\"orderId\":\"o-1\"}");
    }

    @Test
    void aBodyThatHoldsCardDataIsNeverFingerprinted() throws Exception {
        // Same key, different card: the body is ignored on /v1/vault/**, so it replays and nothing guessable is kept.
        post("/v1/vault/tokenize", "k1", "{\"pan\":\"4111111111111111\"}");
        MockHttpServletResponse retry = post("/v1/vault/tokenize", "k1", "{\"pan\":\"4000000000000002\"}");

        assertThat(handlerCalls).hasValue(1);
        assertThat(retry.getContentAsString()).isEqualTo("{\"call\":1}");
        assertThat(redis.toString()).doesNotContain("4111111111111111").doesNotContain("4000000000000002");
    }

    @Test
    void aShownOnceSecretIsNeverStoredAndARetryIsToldItCannotBeReplayed() throws Exception {
        FilterChain secretHandler = (req, res) -> {
            handlerCalls.incrementAndGet();
            ((HttpServletResponse) res).setStatus(201);
            res.getWriter().write("{\"keySecret\":\"pf_secret_value\"}");
        };
        filter.doFilter(request("/v1/merchants/api-keys", "k1", "{}"), new MockHttpServletResponse(), secretHandler);

        assertThat(redis.toString()).doesNotContain("pf_secret_value");

        MockHttpServletResponse retry = new MockHttpServletResponse();
        filter.doFilter(request("/v1/merchants/api-keys", "k1", "{}"), retry, secretHandler);

        assertThat(handlerCalls).hasValue(1);
        assertThat(retry.getContentAsString()).doesNotContain("pf_secret_value");
        assertThat(rejection()).isInstanceOf(IdempotencyResponseUnavailableException.class);
    }

    @Test
    void aSecondRequestWhileTheFirstIsRunningIsAConflictOrARefusalDependingOnWhatItAsks() throws Exception {
        FilterChain nested = (req, res) -> {
            // Arrives while the first request holds the key: the same request, then a different one.
            filter.doFilter(request("/v1/payments", "k1", "{\"orderId\":\"o-1\"}"), new MockHttpServletResponse(), handler);
            filter.doFilter(request("/v1/payments", "k1", "{\"orderId\":\"o-2\"}"), new MockHttpServletResponse(), handler);
            ((HttpServletResponse) res).setStatus(201);
            res.getWriter().write("{}");
        };

        filter.doFilter(request("/v1/payments", "k1", "{\"orderId\":\"o-1\"}"), new MockHttpServletResponse(), nested);

        ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
        verify(resolver, org.mockito.Mockito.times(2)).resolveException(any(), any(), any(), captor.capture());
        assertThat(captor.getAllValues().get(0)).isInstanceOf(IdempotencyConflictException.class);
        assertThat(captor.getAllValues().get(1)).isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(handlerCalls).hasValue(0);
    }

    @Test
    void aResponseStoredBeforeFingerprintsExistedStillReplays() throws Exception {
        String key = context.getMerchantId() + ":POST:/v1/payments:k1";
        redis.put(key, "201|{\"legacy\":true}");

        MockHttpServletResponse response = post("/v1/payments", "k1");

        assertThat(handlerCalls).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("{\"legacy\":true}");
        verify(resolver, never()).resolveException(any(), any(), any(), any());
    }

    @Test
    void aBodyTooLargeToFingerprintIsPassedOnUnguarded() throws Exception {
        byte[] huge = new byte[1024 * 1024 + 1];
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = request("/v1/payments", "k1", "");
            request.setContent(huge);
            filter.doFilter(request, new MockHttpServletResponse(), handler);
        }

        assertThat(handlerCalls).hasValue(2);
        assertThat(redis).isEmpty();
    }
}
