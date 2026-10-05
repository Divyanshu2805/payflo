package com.project.payflo.merchant_service.security;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.enums.UserRole;
import com.project.payflo.common_lib.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Who may do what inside a merchant. The gateway already makes a TEAM member read-only; this adds the
 * checks only a service can make, for the dashboard-only actions: profile, payout account, KYC, users and
 * password. Those need a logged-in person with the right role — an API key (a merchant's backend, which
 * could be a leaked secret) has no say in where the money goes.
 */
@Component
@RequiredArgsConstructor
public class CallerPolicy {

    private final MerchantContext merchantContext;

    /** The email of the dashboard user making the call. */
    public String requireDashboardUser() {
        String email = merchantContext.getUserEmail();
        if (email == null || email.isBlank()) {
            throw new ForbiddenException("DASHBOARD_LOGIN_REQUIRED",
                    "This action needs a dashboard login, not an API key");
        }
        return email;
    }

    /** The email of the dashboard user, who must hold one of the roles. */
    public String requireRole(UserRole... allowed) {
        String email = requireDashboardUser();
        UserRole role = parse(merchantContext.getUserRole());
        if (role == null || !Arrays.asList(allowed).contains(role)) {
            throw new ForbiddenException("ROLE_FORBIDDEN",
                    "Your role (" + (role != null ? role : "unknown") + ") cannot do this");
        }
        return email;
    }

    private static UserRole parse(String role) {
        try {
            return role == null ? null : UserRole.valueOf(role);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
