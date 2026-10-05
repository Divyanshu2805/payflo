package com.project.payflo.common_lib.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Every {@code /v1/admin/**} request must carry {@code X-Platform-Admin: true}, which only the gateway sets, and only
 * after checking the platform admin key (it drops any copy the client sent). A merchant's own credential never
 * reaches an admin endpoint: the gateway refuses it on that path, and this makes a service refuse a request that
 * arrives without the gateway's say-so.
 *
 * <p>It rests on the same trust as {@code X-Merchant-Id}: a caller that can reach a service directly and write headers
 * can claim anything, which is why services are not published beyond the gateway.
 */
public class PlatformAdminFilter extends OncePerRequestFilter {

    private static final String ADMIN_PREFIX = "/v1/admin/";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(ADMIN_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("true".equals(request.getHeader(MerchantContextFilter.PLATFORM_ADMIN_HEADER))) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"errorCode\":\"ADMIN_REQUIRED\",\"errorDescription\":\"This endpoint is for the platform operator\"}");
    }
}
