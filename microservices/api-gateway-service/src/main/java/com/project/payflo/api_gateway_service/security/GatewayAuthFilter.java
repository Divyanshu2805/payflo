package com.project.payflo.api_gateway_service.security;

import com.project.payflo.common_lib.enums.UserRole;
import com.project.payflo.common_lib.exception.RateLimitException;
import com.project.payflo.common_lib.ratelimit.RateLimitResult;
import com.project.payflo.common_lib.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE+1)
public class GatewayAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BASIC_PREFIX = "Basic ";
    private static final String PUBLIC_AUTH_PREFIX = "/v1/auth/";

    private final JwtAuthHandler jwtAuthHandler;
    private final ApiKeyAuthHandler apiKeyAuthHandler;
    private final AdminAuthHandler adminAuthHandler;
    private final PublicRouteMatcher publicRouteMatcher;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;
    private final AuthFailureTracker authFailureTracker;
    private final ClientIpResolver clientIpResolver;
    private final MerchantStatusChecker merchantStatusChecker;
    private final SecurityRouteProperties securityRouteProperties;


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        log.info("Incoming request: {}", request.getRequestURI());

        String clientIp = clientIpResolver.resolve(request);

        if (publicRouteMatcher.isPublic(request.getRequestURI())) {
            if (request.getRequestURI().startsWith(PUBLIC_AUTH_PREFIX) && !publicAuthAllowed(clientIp, response)) {
                return;
            }
            // Public or not, a client never gets to choose the identity headers a service trusts.
            HeaderAugmentingRequestWrapper wrapped = new HeaderAugmentingRequestWrapper(request);
            wrapped.putHeader("X-Client-Ip", clientIp);
            filterChain.doFilter(wrapped, response);
            return;
        }

        // Too many failed attempts from this address: refuse before spending a bcrypt check on it.
        int blockedFor = authFailureTracker.blockedForSeconds(clientIp);
        if (blockedFor > 0) {
            response.setHeader("Retry-After", String.valueOf(blockedFor));
            reject(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
                    "Too many failed authentication attempts");
            return;
        }

        // The platform operator's API takes the admin key and nothing else: a merchant's JWT or API key is not looked
        // at here, so no merchant credential can open it.
        if (AdminAuthHandler.isAdminPath(request.getRequestURI())) {
            authenticateAdmin(request, response, filterChain, clientIp);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        try {
            Map<String, String> identityHeaders = Map.of();
            if (authHeader != null && authHeader.startsWith(BASIC_PREFIX)) {
                identityHeaders = apiKeyAuthHandler.authenticate(authHeader, response);
            } else if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
                identityHeaders = jwtAuthHandler.authenticate(authHeader.substring(BEARER_PREFIX.length()));
            } else {
                authFailureTracker.recordFailure(clientIp);
                reject(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid Authorization header");
                return;
            }

            merchantStatusChecker.requireNotSuspended(identityHeaders.get("X-Merchant-Id"));
            requireWriteAllowed(request, identityHeaders.get("X-User-Role"));

            HeaderAugmentingRequestWrapper wrapped = new HeaderAugmentingRequestWrapper(request);
            identityHeaders.forEach(wrapped::putHeader);
            wrapped.putHeader("X-Client-Ip", clientIp);

            filterChain.doFilter(wrapped, response);
        } catch (RateLimitException e) {
            response.setHeader("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
            reject(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", e.getMessage());
        } catch (MerchantSuspendedException e) {
            reject(response, HttpStatus.FORBIDDEN, "MERCHANT_SUSPENDED", e.getMessage());
        } catch (RoleForbiddenException e) {
            reject(response, HttpStatus.FORBIDDEN, "ROLE_FORBIDDEN", e.getMessage());
        } catch (GatewayAuthenticationException e) {
            authFailureTracker.recordFailure(clientIp);
            reject(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage());
        } catch (Exception e) {
            log.warn("Gateway auth failed for path={}", request.getRequestURI(), e);
            authFailureTracker.recordFailure(clientIp);
            reject(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid credentials");
        }

    }

    private void authenticateAdmin(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain,
                                   String clientIp) throws ServletException, IOException {
        if (!adminAuthHandler.isEnabled()) {
            reject(response, HttpStatus.FORBIDDEN, "ADMIN_API_DISABLED", "The admin API is not enabled");
            return;
        }
        try {
            adminAuthHandler.authenticate(request.getHeader(AdminAuthHandler.ADMIN_KEY_HEADER));
        } catch (GatewayAuthenticationException e) {
            authFailureTracker.recordFailure(clientIp);
            reject(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage());
            return;
        }
        HeaderAugmentingRequestWrapper wrapped = new HeaderAugmentingRequestWrapper(request);
        wrapped.putHeader("X-Platform-Admin", "true");
        wrapped.putHeader("X-Client-Ip", clientIp);
        filterChain.doFilter(wrapped, response);
    }

    // A TEAM member of a merchant can look but not change anything. (Owners and admins, and API keys, can.)
    private static final Set<String> READ_ONLY_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private void requireWriteAllowed(HttpServletRequest request, String role) {
        if (UserRole.TEAM.name().equals(role) && !READ_ONLY_METHODS.contains(request.getMethod())) {
            throw new RoleForbiddenException("Your role is read-only");
        }
    }

    // Signup and login are public, so anyone can call them; limit how fast one address can.
    private boolean publicAuthAllowed(String clientIp, HttpServletResponse response) throws IOException {
        RateLimitResult result;
        try {
            result = rateLimiter.check("public-auth:" + clientIp,
                    securityRouteProperties.getPublicAuthRequestsPerMinute(), 60);
        } catch (Exception e) {
            log.warn("Public route rate limit unavailable, allowing the request", e);
            return true;
        }
        if (result.isAllowed()) {
            return true;
        }
        response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
        reject(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", "Too many requests");
        return false;
    }

    private void reject(HttpServletResponse response, HttpStatus status, String errorCode, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("errorCode", errorCode, "errorDescription", message));
    }
}
