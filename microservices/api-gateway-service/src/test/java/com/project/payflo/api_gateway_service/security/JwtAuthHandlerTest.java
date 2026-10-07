package com.project.payflo.api_gateway_service.security;

import com.project.payflo.common_lib.exception.RateLimitException;
import com.project.payflo.common_lib.ratelimit.RateLimitResult;
import com.project.payflo.common_lib.ratelimit.RateLimiter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthHandlerTest {

    private final JwtVerifier verifier = mock(JwtVerifier.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final JwtAuthHandler handler = new JwtAuthHandler(verifier, rateLimiter, redis);

    private final Claims claims = mock(Claims.class);
    private final long issuedAt = System.currentTimeMillis() - 60_000;

    @BeforeEach
    void aValidToken() {
        ReflectionTestUtils.setField(handler, "requestsPerMinute", 600);
        when(verifier.verify("token")).thenReturn(claims);
        when(verifier.extractMerchantId(claims)).thenReturn("merchant-1");
        when(verifier.extractRole(claims)).thenReturn("ADMIN");
        when(verifier.extractEmail(claims)).thenReturn("Owner@Example.com");
        when(verifier.extractTokenId(claims)).thenReturn("token-1");
        when(verifier.extractIssuedAtMillis(claims)).thenReturn(issuedAt);
        when(rateLimiter.check(anyString(), anyInt(), anyLong())).thenReturn(RateLimitResult.allowed(5));
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenReturn(Arrays.asList(null, null));
    }

    @Test
    void aValidTokenGivesTheMerchantRoleAndUserEmail() {
        Map<String, String> identity = handler.authenticate("token");

        assertThat(identity).containsEntry("X-Merchant-Id", "merchant-1")
                .containsEntry("X-User-Role", "ADMIN")
                .containsEntry("X-User-Email", "Owner@Example.com");
    }

    @Test
    void aBadSignatureOrExpiredTokenIsRefused() {
        when(verifier.verify("bad")).thenThrow(new JwtException("bad"));

        assertThatThrownBy(() -> handler.authenticate("bad")).isInstanceOf(GatewayAuthenticationException.class);
    }

    @Test
    void aTokenWithoutAMerchantOrRoleIsRefused() {
        when(verifier.extractRole(claims)).thenReturn(null);

        assertThatThrownBy(() -> handler.authenticate("token")).isInstanceOf(GatewayAuthenticationException.class);
    }

    @Test
    void aTokenThatWasLoggedOutIsRefused() {
        when(values.multiGet(anyList())).thenReturn(Arrays.asList("1", null));

        assertThatThrownBy(() -> handler.authenticate("token")).isInstanceOf(GatewayAuthenticationException.class);
    }

    @Test
    void aTokenIssuedBeforeTheUsersSessionsWereEndedIsRefused() {
        // sessions ended a moment after this token was issued
        when(values.multiGet(anyList())).thenReturn(Arrays.asList(null, String.valueOf(issuedAt + 1_000)));

        assertThatThrownBy(() -> handler.authenticate("token")).isInstanceOf(GatewayAuthenticationException.class);
    }

    @Test
    void aTokenIssuedAfterTheSessionsWereEndedIsFine() {
        when(values.multiGet(anyList())).thenReturn(Arrays.asList(null, String.valueOf(issuedAt - 1_000)));

        assertThat(handler.authenticate("token")).containsKey("X-Merchant-Id");
    }

    @Test
    void theRevocationKeysAreTheOnesMerchantServiceWrites() {
        handler.authenticate("token");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> keys = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(values).multiGet(keys.capture());
        assertThat(keys.getValue()).containsExactly("jwt:revoked:token-1", "jwt:user-revoked-after:owner@example.com");
    }

    @Test
    void ifRedisCannotBeAskedTheSignedTokenIsStillAccepted() {
        when(values.multiGet(anyList())).thenThrow(new RuntimeException("redis down"));

        assertThat(handler.authenticate("token")).containsKey("X-Merchant-Id");
    }

    @Test
    void anOldTokenWithNoIdOrEmailStillWorks() {
        when(verifier.extractTokenId(claims)).thenReturn(null);
        when(verifier.extractEmail(claims)).thenReturn(null);

        Map<String, String> identity = handler.authenticate("token");

        assertThat(identity).doesNotContainKey("X-User-Email");
    }

    @Test
    void theMerchantIsRateLimited() {
        when(rateLimiter.check(anyString(), anyInt(), anyLong())).thenReturn(RateLimitResult.denied(12));

        assertThatThrownBy(() -> handler.authenticate("token"))
                .isInstanceOfSatisfying(RateLimitException.class, e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(12));
    }

    @SuppressWarnings("unused")
    private static Date unused() {
        return new Date();
    }
}
