package com.project.payflo.merchant_service.service;

import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.UserRole;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.DuplicateResourceException;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.merchant_service.dto.request.MerchantProfileRequests.CreateUserRequest;
import com.project.payflo.merchant_service.dto.response.UserResponse;
import com.project.payflo.merchant_service.entity.AppUser;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.repository.AppUserRepository;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import com.project.payflo.merchant_service.security.CallerPolicy;
import com.project.payflo.merchant_service.security.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The people who can log in to a merchant's dashboard. The owner (the one who signed up) adds and removes
 * them and sets their role: <b>ADMIN</b> can do everything except the payout account, KYC and managing users;
 * <b>TEAM</b> can only look. There is exactly one owner and it can't be removed or demoted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserManagementService {

    private final AppUserRepository appUserRepository;
    private final MerchantRepository merchantRepository;
    private final PasswordEncoder passwordEncoder;
    private final CallerPolicy callerPolicy;
    private final SessionService sessionService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<UserResponse> list(UUID merchantId) {
        callerPolicy.requireRole(UserRole.OWNER, UserRole.ADMIN);
        return appUserRepository.findByMerchant_IdOrderByCreatedAtAsc(merchantId).stream()
                .map(UserResponse::from)
                .toList();
    }

    @Transactional
    public UserResponse create(UUID merchantId, CreateUserRequest request) {
        callerPolicy.requireRole(UserRole.OWNER);
        requireAssignable(request.role());

        if (appUserRepository.findByEmail(request.email()).isPresent()) {
            throw new DuplicateResourceException("DUPLICATE_USER_EMAIL", "A user with this email already exists");
        }
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", merchantId));

        AppUser user = appUserRepository.save(AppUser.builder()
                .email(request.email())
                .merchant(merchant)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(request.role())
                .build());
        auditLogService.record(AuditAction.USER_ADDED, merchantId, "USER", user.getId().toString(),
                Map.of("email", user.getEmail(), "role", user.getRole().name()));
        log.info("User {} added to merchant {} as {}", user.getId(), merchantId, user.getRole());
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse changeRole(UUID merchantId, UUID userId, UserRole role) {
        callerPolicy.requireRole(UserRole.OWNER);
        requireAssignable(role);
        AppUser user = requireNotOwner(merchantId, userId);

        UserRole previous = user.getRole();
        user.setRole(role);
        auditLogService.record(AuditAction.USER_ROLE_CHANGED, merchantId, "USER", userId.toString(),
                Map.of("email", user.getEmail(), "from", previous.name(), "to", role.name()));
        // Their tokens carry the old role: end those sessions so the new one applies from the next login.
        revokeSessionsAfterCommit(user.getEmail());
        return UserResponse.from(appUserRepository.save(user));
    }

    @Transactional
    public void remove(UUID merchantId, UUID userId) {
        callerPolicy.requireRole(UserRole.OWNER);
        AppUser user = requireNotOwner(merchantId, userId);

        appUserRepository.delete(user);
        auditLogService.record(AuditAction.USER_REMOVED, merchantId, "USER", userId.toString(),
                Map.of("email", user.getEmail(), "role", user.getRole().name()));
        revokeSessionsAfterCommit(user.getEmail());
        log.info("User {} removed from merchant {}", userId, merchantId);
    }

    private AppUser requireNotOwner(UUID merchantId, UUID userId) {
        AppUser user = appUserRepository.findByIdAndMerchant_Id(userId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (user.getRole() == UserRole.OWNER) {
            throw new BusinessRuleViolationException("OWNER_PROTECTED", "The owner can't be changed or removed");
        }
        return user;
    }

    // The owner is the one who signed up; there is no second.
    private static void requireAssignable(UserRole role) {
        if (role == UserRole.OWNER) {
            throw new BusinessRuleViolationException("CANNOT_ASSIGN_OWNER", "A merchant has one owner; choose ADMIN or TEAM");
        }
    }

    // Only once the change is committed, so a user can't log straight back in under the old role.
    private void revokeSessionsAfterCommit(String email) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sessionService.revokeAllSessions(email);
                }
            });
        } else {
            sessionService.revokeAllSessions(email);
        }
    }
}
