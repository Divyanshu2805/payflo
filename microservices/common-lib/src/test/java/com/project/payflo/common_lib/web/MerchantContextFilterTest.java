package com.project.payflo.common_lib.web;

import com.project.payflo.common_lib.context.MerchantContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantContextFilterTest {

    private final MerchantContext context = new MerchantContext();
    private final MerchantContextFilter filter = new MerchantContextFilter(context);

    @Test
    void theIdentityHeadersBecomeTheContext() throws Exception {
        UUID merchantId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Merchant-Id", merchantId.toString());
        request.addHeader("X-Key-Id", "pf_test_abc");
        request.addHeader("X-User-Role", "ADMIN");
        request.addHeader("X-User-Email", "owner@example.com");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(context.getMerchantId()).isEqualTo(merchantId);
        assertThat(context.getKeyId()).isEqualTo("pf_test_abc");
        assertThat(context.getUserRole()).isEqualTo("ADMIN");
        assertThat(context.getUserEmail()).isEqualTo("owner@example.com");
    }

    @Test
    void aMalformedMerchantIdIsA400NotAnUnexplained500() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Merchant-Id", "not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("INVALID_MERCHANT_ID");
        assertThat(chain.getRequest()).isNull();
        assertThat(context.getMerchantId()).isNull();
    }

    @Test
    void theAdminMarkerAndTheClientAddressAreReadForTheAuditLog() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Platform-Admin", "true");
        request.addHeader("X-Client-Ip", "203.0.113.9");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(context.isPlatformAdmin()).isTrue();
        assertThat(context.getClientIp()).isEqualTo("203.0.113.9");
    }

    @Test
    void anythingButTrueIsNotAdminAndALongAddressIsCut() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Platform-Admin", "TRUE");
        request.addHeader("X-Client-Ip", "9".repeat(200));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(context.isPlatformAdmin()).isFalse();
        assertThat(context.getClientIp()).hasSize(64);
    }

    @Test
    void noHeadersLeaveTheContextEmpty() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(context.getMerchantId()).isNull();
        assertThat(context.getUserEmail()).isNull();
    }
}
