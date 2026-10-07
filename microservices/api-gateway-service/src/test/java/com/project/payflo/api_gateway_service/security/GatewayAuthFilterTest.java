package com.project.payflo.api_gateway_service.security;

import com.project.payflo.common_lib.exception.RateLimitException;
import com.project.payflo.common_lib.ratelimit.RateLimitResult;
import com.project.payflo.common_lib.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayAuthFilterTest {

    private final JwtAuthHandler jwtAuthHandler = mock(JwtAuthHandler.class);
    private final ApiKeyAuthHandler apiKeyAuthHandler = mock(ApiKeyAuthHandler.class);
    private final PublicRouteMatcher publicRouteMatcher = mock(PublicRouteMatcher.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final AuthFailureTracker failureTracker = mock(AuthFailureTracker.class);
    private final MerchantStatusChecker statusChecker = mock(MerchantStatusChecker.class);
    private final SecurityRouteProperties properties = new SecurityRouteProperties();

    private static final String ADMIN_KEY = "test-admin-key-0123456789abcdef";

    private GatewayAuthFilter filter;

    @BeforeEach
    void setUp() {
        properties.setAdminApiKey(ADMIN_KEY);
        filter = new GatewayAuthFilter(jwtAuthHandler, apiKeyAuthHandler, new AdminAuthHandler(properties), publicRouteMatcher,
                JsonMapper.builder().build(), rateLimiter, failureTracker, new ClientIpResolver(properties),
                statusChecker, properties);
        when(rateLimiter.check(anyString(), anyInt(), anyLong())).thenReturn(RateLimitResult.allowed(10));
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.setRemoteAddr("203.0.113.9");
        return request;
    }

    private static boolean sawHeader(MockFilterChain chain, String name) {
        HttpServletRequest forwarded = (HttpServletRequest) chain.getRequest();
        return forwarded != null && Collections.list(forwarded.getHeaderNames()).stream()
                .anyMatch(h -> h.equalsIgnoreCase(name));
    }

    @Test
    void aPublicRouteForwardsWithoutAnyClientSuppliedIdentityHeaders() throws Exception {
        when(publicRouteMatcher.isPublic("/v1/auth/login")).thenReturn(true);
        MockHttpServletRequest request = request("/v1/auth/login");
        request.addHeader("X-Merchant-Id", "11111111-1111-1111-1111-111111111111");
        request.addHeader("x-user-role", "OWNER");
        request.addHeader("Content-Type", "application/json");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(sawHeader(chain, "X-Merchant-Id")).isFalse();
        assertThat(sawHeader(chain, "X-User-Role")).isFalse();
        assertThat(sawHeader(chain, "Content-Type")).isTrue();
    }

    @Test
    void anAuthenticatedRequestCarriesOnlyTheHeadersTheGatewaySet() throws Exception {
        when(publicRouteMatcher.isPublic("/v1/orders")).thenReturn(false);
        when(jwtAuthHandler.authenticate("token"))
                .thenReturn(Map.of("X-Merchant-Id", "22222222-2222-2222-2222-222222222222", "X-User-Role", "OWNER"));
        MockHttpServletRequest request = request("/v1/orders");
        request.addHeader("Authorization", "Bearer token");
        request.addHeader("X-Merchant-Id", "99999999-9999-9999-9999-999999999999"); // someone else's
        request.addHeader("X-Key-Id", "pf_live_forged");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest forwarded = (HttpServletRequest) chain.getRequest();
        assertThat(forwarded.getHeader("X-Merchant-Id")).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(forwarded.getHeader("X-Key-Id")).isNull();
    }

    @Test
    void aSuspendedMerchantIsForbidden() throws Exception {
        when(jwtAuthHandler.authenticate("token")).thenReturn(Map.of("X-Merchant-Id", "m1", "X-User-Role", "OWNER"));
        doThrow(new MerchantSuspendedException()).when(statusChecker).requireNotSuspended("m1");
        MockHttpServletRequest request = request("/v1/orders");
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("MERCHANT_SUSPENDED");
        assertThat(chain.getRequest()).isNull();
        verify(failureTracker, never()).recordFailure(anyString());
    }

    private MockHttpServletRequest requestAs(String role, String method) {
        when(jwtAuthHandler.authenticate("token"))
                .thenReturn(Map.of("X-Merchant-Id", "m1", "X-User-Role", role, "X-User-Email", "user@example.com"));
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/v1/orders");
        request.setRequestURI("/v1/orders");
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("Authorization", "Bearer token");
        return request;
    }

    @Test
    void aTeamMemberCanReadButNotChangeAnything() throws Exception {
        for (String method : new String[]{"GET", "HEAD", "OPTIONS"}) {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(requestAs("TEAM", method), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).as(method).isNotNull();
        }
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(requestAs("TEAM", method), response, chain);
            assertThat(response.getStatus()).as(method).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("ROLE_FORBIDDEN");
            assertThat(chain.getRequest()).as(method).isNull();
        }
    }

    @Test
    void ownersAndAdminsCanWrite() throws Exception {
        for (String role : new String[]{"OWNER", "ADMIN"}) {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(requestAs(role, "POST"), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).as(role).isNotNull();
        }
    }

    @Test
    void theUsersEmailReachesTheServiceButAClientsCannot() throws Exception {
        MockHttpServletRequest request = requestAs("OWNER", "GET");
        request.addHeader("X-User-Email", "forged@example.com");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(((HttpServletRequest) chain.getRequest()).getHeader("X-User-Email")).isEqualTo("user@example.com");
    }

    @Test
    void aBadCredentialIsRefusedAndCountedAgainstTheClientAddress() throws Exception {
        when(jwtAuthHandler.authenticate("bad")).thenThrow(new GatewayAuthenticationException("Invalid or expired token"));
        MockHttpServletRequest request = request("/v1/orders");
        request.addHeader("Authorization", "Bearer bad");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        verify(failureTracker).recordFailure("203.0.113.9");
    }

    @Test
    void aMissingCredentialIsRefusedAndCounted() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/v1/orders"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        verify(failureTracker).recordFailure("203.0.113.9");
    }

    @Test
    void anAddressWithTooManyFailuresIsRefusedBeforeAnyCredentialIsChecked() throws Exception {
        when(failureTracker.blockedForSeconds("203.0.113.9")).thenReturn(42);
        MockHttpServletRequest request = request("/v1/orders");
        request.addHeader("Authorization", "Basic abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("42");
        verify(apiKeyAuthHandler, never()).authenticate(anyString(), any());
    }

    @Test
    void signupAndLoginAreRateLimitedPerClientAddress() throws Exception {
        when(publicRouteMatcher.isPublic("/v1/auth/login")).thenReturn(true);
        when(rateLimiter.check("public-auth:203.0.113.9", 120, 60)).thenReturn(RateLimitResult.denied(30));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/v1/auth/login"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void theRateLimitIsFailOpenWhenRedisIsDown() throws Exception {
        when(publicRouteMatcher.isPublic("/v1/auth/login")).thenReturn(true);
        when(rateLimiter.check(anyString(), anyInt(), anyLong())).thenThrow(new RuntimeException("redis down"));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/v1/auth/login"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void anOverLimitCredentialIsA429NotACountedFailure() throws Exception {
        when(jwtAuthHandler.authenticate("t")).thenThrow(new RateLimitException("Too many requests", 7));
        MockHttpServletRequest request = request("/v1/orders");
        request.addHeader("Authorization", "Bearer t");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        verify(failureTracker, never()).recordFailure(anyString());
    }

    // ---- the admin API

    private MockHttpServletRequest adminRequest(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.setRemoteAddr("203.0.113.9");
        return request;
    }

    @Test
    void theAdminKeyOpensTheAdminApiAndTheGatewayMarksTheRequestAsTheOperators() throws Exception {
        MockHttpServletRequest request = adminRequest("/v1/admin/merchants/abc/suspend");
        request.addHeader("X-Admin-Key", ADMIN_KEY);
        request.addHeader("X-Platform-Admin", "forged-by-client");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest forwarded = (HttpServletRequest) chain.getRequest();
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getHeader("X-Platform-Admin")).isEqualTo("true");
        assertThat(forwarded.getHeader("X-Client-Ip")).isEqualTo("203.0.113.9");
        assertThat(forwarded.getHeader("X-Merchant-Id")).isNull();
        // no merchant credential was looked at, or needed
        verify(jwtAuthHandler, never()).authenticate(anyString());
        verify(statusChecker, never()).requireNotSuspended(any());
    }

    @Test
    void aWrongOrMissingAdminKeyIsRefusedAndCountedAgainstTheCaller() throws Exception {
        for (String presented : new String[]{"not-the-key", ""}) {
            MockHttpServletRequest request = adminRequest("/v1/admin/merchants/abc/suspend");
            request.addHeader("X-Admin-Key", presented);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).as(presented).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
        MockHttpServletResponse noHeader = new MockHttpServletResponse();
        filter.doFilter(adminRequest("/v1/admin/merchants"), noHeader, new MockFilterChain());
        assertThat(noHeader.getStatus()).isEqualTo(401);
        verify(failureTracker, org.mockito.Mockito.times(3)).recordFailure("203.0.113.9");
    }

    @Test
    void aMerchantsOwnCredentialNeverOpensTheAdminApi() throws Exception {
        MockHttpServletRequest request = adminRequest("/v1/admin/merchants");
        request.addHeader("Authorization", "Bearer a-valid-merchant-jwt");
        request.addHeader("X-Platform-Admin", "true");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        verify(jwtAuthHandler, never()).authenticate(anyString());
        verify(apiKeyAuthHandler, never()).authenticate(anyString(), any());
    }

    @Test
    void theAdminApiIsOffWhenNoKeyIsConfigured() throws Exception {
        properties.setAdminApiKey("");
        GatewayAuthFilter withoutAdmin = new GatewayAuthFilter(jwtAuthHandler, apiKeyAuthHandler,
                new AdminAuthHandler(properties), publicRouteMatcher, JsonMapper.builder().build(), rateLimiter,
                failureTracker, new ClientIpResolver(properties), statusChecker, properties);
        MockHttpServletRequest request = adminRequest("/v1/admin/merchants");
        request.addHeader("X-Admin-Key", "anything");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        withoutAdmin.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ADMIN_API_DISABLED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void theAdminKeyOpensNothingOutsideTheAdminApiAndAForgedAdminHeaderIsDroppedEverywhere() throws Exception {
        MockHttpServletRequest request = request("/v1/orders");
        request.addHeader("X-Admin-Key", ADMIN_KEY);
        request.addHeader("X-Platform-Admin", "true");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401); // an ordinary route still wants a merchant credential
        assertThat(chain.getRequest()).isNull();

        when(publicRouteMatcher.isPublic("/v1/auth/login")).thenReturn(true);
        MockHttpServletRequest publicRequest = request("/v1/auth/login");
        publicRequest.addHeader("X-Platform-Admin", "true");
        publicRequest.addHeader("X-Client-Ip", "10.9.9.9");
        MockFilterChain publicChain = new MockFilterChain();

        filter.doFilter(publicRequest, new MockHttpServletResponse(), publicChain);

        HttpServletRequest forwarded = (HttpServletRequest) publicChain.getRequest();
        assertThat(forwarded.getHeader("X-Platform-Admin")).isNull();
        assertThat(forwarded.getHeader("X-Client-Ip")).isEqualTo("203.0.113.9"); // the gateway's own, not the client's
    }

    @Test
    void theClientAddressComesFromTheConfiguredProxyHeader() throws Exception {
        properties.setClientIpHeader("X-Forwarded-For");
        MockHttpServletRequest request = request("/v1/orders");
        request.addHeader("X-Forwarded-For", "198.51.100.4, 10.0.0.1");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(failureTracker).recordFailure("198.51.100.4");
    }
}
