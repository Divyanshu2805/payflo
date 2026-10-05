package com.project.payflo.common_lib.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformAdminFilterTest {

    private final PlatformAdminFilter filter = new PlatformAdminFilter();

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        return request;
    }

    @Test
    void anAdminRequestWithTheGatewaysMarkerGoesThrough() throws Exception {
        MockHttpServletRequest request = request("/v1/admin/merchants/x/suspend");
        request.addHeader("X-Platform-Admin", "true");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void anAdminRequestWithoutItIsRefusedWhoeverItClaimsToBe() throws Exception {
        MockHttpServletRequest request = request("/v1/admin/merchants/x/suspend");
        request.addHeader("X-Merchant-Id", "11111111-1111-1111-1111-111111111111");
        request.addHeader("X-User-Role", "OWNER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_REQUIRED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void onlyTheExactValueTrueCounts() throws Exception {
        for (String value : new String[]{"false", "TRUE", "1", "yes", ""}) {
            MockHttpServletRequest request = request("/v1/admin/audit-log");
            request.addHeader("X-Platform-Admin", value);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).as(value).isEqualTo(403);
        }
    }

    @Test
    void everyOtherPathIsLeftAlone() throws Exception {
        for (String path : new String[]{"/v1/orders", "/v1/merchants/me", "/internal/audit", "/v1/administrators", "/v1/adminx/merchants"}) {
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request(path), new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).as(path).isNotNull();
        }
    }
}
