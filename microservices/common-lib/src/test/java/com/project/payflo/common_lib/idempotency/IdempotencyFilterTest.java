package com.project.payflo.common_lib.idempotency;

import com.project.payflo.common_lib.context.MerchantContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IdempotencyFilterTest {

    private final Map<String, String> redis = new HashMap<>();
    private final IdempotencyStore store = new IdempotencyStore() {
        @Override public boolean setIfAbsent(String key, Duration ttl) { return redis.putIfAbsent(key, IN_PROGRESS) == null; }
        @Override public void store(String key, String value, Duration ttl) { redis.put(key, value); }
        @Override public Optional<String> get(String key) { return Optional.ofNullable(redis.get(key)); }
        @Override public void delete(String key) { redis.remove(key); }
    };

    private final MerchantContext context = new MerchantContext();
    private final IdempotencyFilter filter =
            new IdempotencyFilter(context, store, mock(HandlerExceptionResolver.class));
    private final AtomicInteger handlerCalls = new AtomicInteger();

    // A handler that answers 201 with a body naming the call it was.
    private final FilterChain handler = (req, res) -> {
        res.setContentType("application/json");
        res.getWriter().write("{\"call\":" + handlerCalls.incrementAndGet() + "}");
        ((jakarta.servlet.http.HttpServletResponse) res).setStatus(201);
    };

    @BeforeEach
    void authenticated() {
        context.setMerchantId(UUID.randomUUID());
    }

    private MockHttpServletResponse post(String path, String key) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader("X-Idempotency-Key", key);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, handler);
        return response;
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
        IdempotencyFilter anonymousFilter =
                new IdempotencyFilter(anonymous, store, mock(HandlerExceptionResolver.class));

        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/login");
            request.addHeader("X-Idempotency-Key", "shared");
            anonymousFilter.doFilter(request, new MockHttpServletResponse(), handler);
        }

        assertThat(handlerCalls).hasValue(2);
        assertThat(redis).isEmpty();
    }

    @Test
    void aFailedFirstRequestReleasesTheKey() throws Exception {
        FilterChain failing = (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(500);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
        request.addHeader("X-Idempotency-Key", "k1");
        filter.doFilter(request, new MockHttpServletResponse(), failing);

        assertThat(post("/v1/payments", "k1").getStatus()).isEqualTo(201);
        assertThat(handlerCalls).hasValue(1);
    }
}
