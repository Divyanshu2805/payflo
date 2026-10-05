package com.project.payflo.api_gateway_service.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderAugmentingRequestWrapperTest {

    private HeaderAugmentingRequestWrapper wrapperFor(MockHttpServletRequest request) {
        return new HeaderAugmentingRequestWrapper(request);
    }

    @Test
    void dropsEveryClientSuppliedIdentityHeaderInAnyCase() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Merchant-Id", "forged");
        request.addHeader("x-key-id", "forged");
        request.addHeader("X-USER-ROLE", "forged");
        request.addHeader("X-Environment", "LIVE");
        HeaderAugmentingRequestWrapper wrapper = wrapperFor(request);

        for (String name : new String[]{"X-Merchant-Id", "X-Key-Id", "X-User-Role", "X-Environment", "x-merchant-id"}) {
            assertThat(wrapper.getHeader(name)).as(name).isNull();
            assertThat(Collections.list(wrapper.getHeaders(name))).as(name).isEmpty();
        }
        assertThat(Collections.list(wrapper.getHeaderNames())).isEmpty();
    }

    @Test
    void keepsEveryOtherHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer abc");
        request.addHeader("X-Idempotency-Key", "k1");
        HeaderAugmentingRequestWrapper wrapper = wrapperFor(request);

        assertThat(wrapper.getHeader("Authorization")).isEqualTo("Bearer abc");
        assertThat(wrapper.getHeader("X-Idempotency-Key")).isEqualTo("k1");
        assertThat(Collections.list(wrapper.getHeaderNames())).containsExactlyInAnyOrder("Authorization", "X-Idempotency-Key");
    }

    @Test
    void theGatewaysOwnValueReplacesTheClientsWhateverTheCase() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-merchant-id", "forged");
        HeaderAugmentingRequestWrapper wrapper = wrapperFor(request);
        wrapper.putHeader("X-Merchant-Id", "real");

        assertThat(wrapper.getHeader("X-Merchant-Id")).isEqualTo("real");
        assertThat(wrapper.getHeader("x-merchant-id")).isEqualTo("real");
        assertThat(Collections.list(wrapper.getHeaders("X-MERCHANT-ID"))).containsExactly("real");
        assertThat(Collections.list(wrapper.getHeaderNames())).containsExactly("X-Merchant-Id");
    }
}
