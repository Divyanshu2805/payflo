package com.project.payflo.merchant_service.service;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.UserRole;
import com.project.payflo.common_lib.exception.BusinessRuleViolationException;
import com.project.payflo.common_lib.exception.DuplicateResourceException;
import com.project.payflo.common_lib.exception.ForbiddenException;
import com.project.payflo.common_lib.exception.ResourceNotFoundException;
import com.project.payflo.merchant_service.dto.request.MerchantProfileRequests.CreateUserRequest;
import com.project.payflo.merchant_service.dto.response.UserResponse;
import com.project.payflo.merchant_service.entity.AppUser;
import com.project.payflo.merchant_service.entity.Merchant;
import com.project.payflo.merchant_service.repository.AppUserRepository;
import com.project.payflo.merchant_service.repository.MerchantRepository;
import com.project.payflo.merchant_service.security.CallerPolicy;
import com.project.payflo.merchant_service.security.SessionService;
import org.junit.jupiter.api.BeforeEach;
import com.project.payflo.merchant_service.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserManagementServiceTest {

    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final SessionService sessions = mock(SessionService.class);
    private final MerchantContext context = new MerchantContext();

    private final AuditLogService audit = mock(AuditLogService.class);
    private final UserManagementService service = new UserManagementService(
            appUserRepository, merchantRepository, passwordEncoder, new CallerPolicy(context), sessions, audit);

    private final UUID merchantId = UUID.randomUUID();
    private Merchant merchant;
    private AppUser owner;
    private AppUser member;

    private void callerIs(String role) {
        context.setUserEmail("owner@example.com");
        context.setUserRole(role);
    }

    @BeforeEach
    void anOwnerAndATeamMember() {
        merchant = Merchant.builder().id(merchantId).build();
        owner = AppUser.builder().id(UUID.randomUUID()).email("owner@example.com").merchant(merchant).role(UserRole.OWNER).build();
        member = AppUser.builder().id(UUID.randomUUID()).email("member@example.com").merchant(merchant).role(UserRole.TEAM).build();
        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));
        when(appUserRepository.findByIdAndMerchant_Id(owner.getId(), merchantId)).thenReturn(Optional.of(owner));
        when(appUserRepository.findByIdAndMerchant_Id(member.getId(), merchantId)).thenReturn(Optional.of(member));
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(inv -> {
            AppUser saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID()); // as persisting does
            return saved;
        });
        when(passwordEncoder.encode("a-password-1")).thenReturn("hashed");
        callerIs("OWNER");
    }

    private CreateUserRequest newUser(UserRole role) {
        return new CreateUserRequest("new@example.com", "a-password-1", role);
    }

    @Test
    void theOwnerCanAddAnAdminOrATeamMember() {
        UserResponse created = service.create(merchantId, newUser(UserRole.ADMIN));

        assertThat(created.role()).isEqualTo(UserRole.ADMIN);
        assertThat(created.email()).isEqualTo("new@example.com");
    }

    @Test
    void thePasswordIsStoredOnlyAsAHash() {
        service.create(merchantId, newUser(UserRole.TEAM));

        var saved = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");
    }

    @Test
    void thereIsOnlyOneOwner() {
        assertThatThrownBy(() -> service.create(merchantId, newUser(UserRole.OWNER)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("CANNOT_ASSIGN_OWNER"));
        assertThatThrownBy(() -> service.changeRole(merchantId, member.getId(), UserRole.OWNER))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void anEmailThatAlreadyLogsInCannotBeAddedAgain() {
        when(appUserRepository.findByEmail("new@example.com")).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.create(merchantId, newUser(UserRole.TEAM)))
                .isInstanceOfSatisfying(DuplicateResourceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("DUPLICATE_USER_EMAIL"));
    }

    @Test
    void onlyTheOwnerManagesUsers() {
        callerIs("ADMIN");
        assertThatThrownBy(() -> service.create(merchantId, newUser(UserRole.TEAM))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.changeRole(merchantId, member.getId(), UserRole.ADMIN)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.remove(merchantId, member.getId())).isInstanceOf(ForbiddenException.class);

        callerIs("TEAM");
        assertThatThrownBy(() -> service.create(merchantId, newUser(UserRole.TEAM))).isInstanceOf(ForbiddenException.class);
        verify(appUserRepository, never()).save(any());
        verify(appUserRepository, never()).delete(any());
    }

    @Test
    void adminsAndOwnersCanListTheUsersButATeamMemberCannot() {
        when(appUserRepository.findByMerchant_IdOrderByCreatedAtAsc(merchantId)).thenReturn(List.of(owner, member));

        callerIs("ADMIN");
        assertThat(service.list(merchantId)).hasSize(2);

        callerIs("TEAM");
        assertThatThrownBy(() -> service.list(merchantId)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void changingARoleEndsThatUsersSessionsSoTheNewRoleApplies() {
        UserResponse changed = service.changeRole(merchantId, member.getId(), UserRole.ADMIN);

        assertThat(changed.role()).isEqualTo(UserRole.ADMIN);
        verify(sessions).revokeAllSessions("member@example.com");
    }

    @Test
    void addingChangingAndRemovingUsersAreEachRecordedInTheAuditLog() {
        UserResponse created = service.create(merchantId, newUser(UserRole.TEAM));
        service.changeRole(merchantId, member.getId(), UserRole.ADMIN);
        service.remove(merchantId, member.getId());

        verify(audit).record(AuditAction.USER_ADDED, merchantId, "USER", created.id().toString(),
                Map.of("email", "new@example.com", "role", "TEAM"));
        verify(audit).record(AuditAction.USER_ROLE_CHANGED, merchantId, "USER", member.getId().toString(),
                Map.of("email", "member@example.com", "from", "TEAM", "to", "ADMIN"));
        verify(audit).record(AuditAction.USER_REMOVED, merchantId, "USER", member.getId().toString(),
                Map.of("email", "member@example.com", "role", "ADMIN"));
    }

    @Test
    void whatTheCallerMayNotDoIsNotRecordedBecauseNothingHappened() {
        callerIs("ADMIN");

        assertThatThrownBy(() -> service.changeRole(merchantId, member.getId(), UserRole.ADMIN)).isInstanceOf(ForbiddenException.class);

        verify(audit, never()).record(any(AuditAction.class), any(), any(), any(), any());
    }

    @Test
    void removingAUserDeletesThemAndEndsTheirSessions() {
        service.remove(merchantId, member.getId());

        verify(appUserRepository).delete(member);
        verify(sessions).revokeAllSessions("member@example.com");
    }

    @Test
    void theOwnerCannotBeChangedOrRemoved() {
        assertThatThrownBy(() -> service.remove(merchantId, owner.getId()))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("OWNER_PROTECTED"));
        assertThatThrownBy(() -> service.changeRole(merchantId, owner.getId(), UserRole.TEAM))
                .isInstanceOf(BusinessRuleViolationException.class);
        verify(appUserRepository, never()).delete(any());
    }

    @Test
    void aUserOfAnotherMerchantIsNotFound() {
        assertThatThrownBy(() -> service.remove(merchantId, UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }
}
