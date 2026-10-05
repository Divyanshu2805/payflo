package com.project.payflo.common_lib.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalApiAuthFilterTest {

    private final InternalApiAuthFilter filter = new InternalApiAuthFilter("s3cret-token-value");

    private MockHttpServletResponse call(String path, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        if (token != null) request.addHeader(InternalApiAuthFilter.TOKEN_HEADER, token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void internalCallsWithTheTokenPass() throws Exception {
        MockHttpServletResponse response = call("/internal/vault/charge", "s3cret-token-value");

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void internalCallsWithoutOrWithAWrongTokenAreRefused() throws Exception {
        assertThat(call("/internal/api-keys/pf_test_x", null).getStatus()).isEqualTo(401);
        assertThat(call("/internal/api-keys/pf_test_x", "wrong").getStatus()).isEqualTo(401);
        assertThat(call("/internal/api-keys/pf_test_x", "").getStatus()).isEqualTo(401);
        assertThat(call("/internal/api-keys/pf_test_x", null).getContentAsString()).contains("UNAUTHORIZED");
    }

    @Test
    void otherPathsAreNotAffected() throws Exception {
        assertThat(call("/v1/orders", null).getStatus()).isEqualTo(200);
        assertThat(call("/actuator/health", null).getStatus()).isEqualTo(200);
    }

    @Test
    void aBlankTokenIsRefusedAtStartupRatherThanAcceptingAnEmptyHeader() {
        assertThatThrownBy(() -> new InternalApiAuthFilter("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new InternalApiAuthFilter("  ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new InternalApiAuthFilter(null)).isInstanceOf(IllegalStateException.class);
    }
}
