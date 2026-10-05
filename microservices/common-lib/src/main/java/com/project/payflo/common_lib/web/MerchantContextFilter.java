package com.project.payflo.common_lib.web;


import com.project.payflo.common_lib.context.MerchantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@RequiredArgsConstructor
public class MerchantContextFilter extends OncePerRequestFilter {

    public static final String MERCHANT_ID_HEADER = "X-Merchant-Id";
    public static final String KEY_ID_HEADER = "X-Key-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";
    public static final String USER_EMAIL_HEADER = "X-User-Email";
    public static final String PLATFORM_ADMIN_HEADER = "X-Platform-Admin";
    public static final String CLIENT_IP_HEADER = "X-Client-Ip";

    private final MerchantContext merchantContext;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String merchantIdHeader = request.getHeader(MERCHANT_ID_HEADER);
        if (merchantIdHeader != null && !merchantIdHeader.isBlank()) {
            try {
                merchantContext.setMerchantId(UUID.fromString(merchantIdHeader.trim()));
            } catch (IllegalArgumentException malformed) {
                // Only the gateway sets this header, so a bad value means a broken caller. Say so, rather than
                // letting the exception out of the filter as an unexplained 500.
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"errorCode\":\"INVALID_MERCHANT_ID\",\"errorDescription\":\"" + MERCHANT_ID_HEADER + " is not a valid id\"}");
                return;
            }
        }

        String keyId = request.getHeader(KEY_ID_HEADER);
        if (keyId != null && !keyId.isBlank()) {
            merchantContext.setKeyId(keyId);
        }

        String userRole = request.getHeader(USER_ROLE_HEADER);
        if (userRole != null && !userRole.isBlank()) {
            merchantContext.setUserRole(userRole);
        }
        String userEmail = request.getHeader(USER_EMAIL_HEADER);
        if (userEmail != null && !userEmail.isBlank()) {
            merchantContext.setUserEmail(userEmail);
        }
        merchantContext.setPlatformAdmin("true".equals(request.getHeader(PLATFORM_ADMIN_HEADER)));
        String clientIp = request.getHeader(CLIENT_IP_HEADER);
        if (clientIp != null && !clientIp.isBlank()) {
            // Only recorded, never trusted for a decision; bounded so a broken caller can't fill a column with it.
            merchantContext.setClientIp(clientIp.length() > 64 ? clientIp.substring(0, 64) : clientIp);
        }

        filterChain.doFilter(request, response);
    }
}
