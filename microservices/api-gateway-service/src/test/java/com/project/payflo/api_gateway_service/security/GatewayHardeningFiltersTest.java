package com.project.payflo.api_gateway_service.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayHardeningFiltersTest {

    private final AtomicInteger reached = new AtomicInteger();
    private final FilterChain chain = (req, res) -> reached.incrementAndGet();

    // ---- security headers

    @Test
    void everyResponseCarriesTheSecurityHeaders() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", "/v1/orders"), response, chain);

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Content-Security-Policy")).contains("default-src 'none'").contains("frame-ancestors 'none'");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(reached).hasValue(1);
    }

    @Test
    void strictTransportSecurityIsSentOnlyOverASecureConnection() throws Exception {
        MockHttpServletResponse plain = new MockHttpServletResponse();
        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", "/"), plain, chain);
        assertThat(plain.getHeader("Strict-Transport-Security")).isNull();

        MockHttpServletRequest secure = new MockHttpServletRequest("GET", "/");
        secure.setSecure(true);
        MockHttpServletResponse https = new MockHttpServletResponse();
        new SecurityHeadersFilter().doFilter(secure, https, chain);
        assertThat(https.getHeader("Strict-Transport-Security")).contains("max-age=");
    }

    @Test
    void aHeaderSetByTheFilterSurvivesTheServiceAnswering() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain answering = (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401);

        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", "/"), response, answering);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    // ---- request size

    private static MockHttpServletRequest post(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/orders");
        request.setContent(body);
        return request;
    }

    @Test
    void aBodyWithinTheLimitPassesThroughUnchanged() throws Exception {
        String[] seen = new String[1];
        FilterChain reading = (req, res) -> seen[0] = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        new RequestSizeLimitFilter(100).doFilter(post("{\"a\":1}".getBytes(StandardCharsets.UTF_8)), new MockHttpServletResponse(), reading);

        assertThat(seen[0]).isEqualTo("{\"a\":1}");
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedWithoutReadingAByte() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestSizeLimitFilter(100).doFilter(post(new byte[101]), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("REQUEST_TOO_LARGE");
        assertThat(reached).hasValue(0);
    }

    @Test
    void aBodyExactlyAtTheLimitIsAllowed() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestSizeLimitFilter(100).doFilter(post(new byte[100]), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(reached).hasValue(1);
    }

    @Test
    void aStreamedBodyThatGrowsPastTheLimitIsCutOffAndRefused() throws Exception {
        // No Content-Length (-1), as with chunked uploads: the filter has to count as it goes
        MockHttpServletRequest noLength = new MockHttpServletRequest("POST", "/v1/orders") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        noLength.setContent(new byte[500]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain drain = (req, res) -> {
            req.getInputStream().readAllBytes();
            reached.incrementAndGet();
        };

        new RequestSizeLimitFilter(100).doFilter(noLength, response, drain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("REQUEST_TOO_LARGE");
        assertThat(reached).hasValue(0); // the handler never finished reading
    }

    @Test
    void anUnrelatedFailureIsNotMistakenForAnOversizedBody() {
        FilterChain failing = (req, res) -> {
            throw new IOException("connection reset");
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new RequestSizeLimitFilter(100).doFilter(post(new byte[10]), new MockHttpServletResponse(), failing))
                .isInstanceOf(IOException.class).hasMessage("connection reset");
    }
}
