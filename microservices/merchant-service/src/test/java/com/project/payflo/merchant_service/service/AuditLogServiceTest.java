package com.project.payflo.merchant_service.service;

import com.project.payflo.common_lib.context.MerchantContext;
import com.project.payflo.common_lib.dto.AuditEntryRequest;
import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.AuditActorType;
import com.project.payflo.merchant_service.entity.AuditLog;
import com.project.payflo.merchant_service.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogServiceTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final MerchantContext context = new MerchantContext();
    private final AuditLogService service = new AuditLogService(repository, context);

    private final UUID merchantId = UUID.randomUUID();

    private AuditLog saved() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void aDashboardUsersActionIsRecordedUnderTheirEmailAndAddress() {
        context.setUserEmail("owner@example.com");
        context.setClientIp("203.0.113.9");

        service.record(AuditAction.USER_ADDED, merchantId, "USER", "u1", Map.of("role", "TEAM"));

        AuditLog entry = saved();
        assertThat(entry.getActorType()).isEqualTo(AuditActorType.USER);
        assertThat(entry.getActor()).isEqualTo("owner@example.com");
        assertThat(entry.getClientIp()).isEqualTo("203.0.113.9");
        assertThat(entry.getMerchantId()).isEqualTo(merchantId);
        assertThat(entry.getAction()).isEqualTo(AuditAction.USER_ADDED);
        assertThat(entry.getTargetType()).isEqualTo("USER");
        assertThat(entry.getDetails()).containsEntry("role", "TEAM");
        assertThat(entry.getOccurredAt()).isNotNull();
    }

    @Test
    void anApiKeysActionIsRecordedUnderItsPublicId() {
        context.setKeyId("pf_live_abc");

        service.record(AuditAction.API_KEY_ROTATED, merchantId, "API_KEY", "k1", null);

        assertThat(saved().getActorType()).isEqualTo(AuditActorType.API_KEY);
        assertThat(saved().getActor()).isEqualTo("pf_live_abc");
    }

    @Test
    void theOperatorIsTheOperatorEvenIfARequestAlsoNamesAUser() {
        context.setPlatformAdmin(true);
        context.setUserEmail("someone@example.com"); // can't happen through the gateway, but the operator wins

        service.record(AuditAction.MERCHANT_SUSPENDED, merchantId, "MERCHANT", merchantId.toString(), Map.of("reason", "fraud"));

        AuditLog entry = saved();
        assertThat(entry.getActorType()).isEqualTo(AuditActorType.PLATFORM_ADMIN);
        assertThat(entry.getActor()).isEqualTo("platform-admin");
    }

    @Test
    void withNoRequestBehindItTheActorIsTheSystem() {
        MerchantContext noRequest = mock(MerchantContext.class);
        when(noRequest.isPlatformAdmin()).thenThrow(new IllegalStateException("No thread-bound request found"));
        AuditLogService outsideARequest = new AuditLogService(repository, noRequest);

        outsideARequest.record(AuditAction.KYC_VERIFIED, merchantId, "MERCHANT", merchantId.toString(), null);

        assertThat(saved().getActorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(saved().getClientIp()).isNull();
    }

    @Test
    void anythingThatLooksLikeASecretIsReplacedBeforeItIsStored() {
        context.setUserEmail("owner@example.com");

        service.record(AuditAction.API_KEY_CREATED, merchantId, "API_KEY", "k1", Map.of(
                "keyId", "pf_test_abc", "keySecret", "s3cret-value", "newPassword", "hunter2", "refreshToken", "t0ken",
                "secretHash", "$2a$10$abc", "cvv", "123", "pan", "4111111111111111"));

        Map<String, Object> details = saved().getDetails();
        assertThat(details).containsEntry("keyId", "pf_test_abc");
        for (String name : new String[]{"keySecret", "newPassword", "refreshToken", "secretHash", "cvv", "pan"}) {
            assertThat(details).as(name).containsEntry(name, "[redacted]");
        }
        assertThat(details.values()).doesNotContain("s3cret-value", "hunter2", "4111111111111111");
    }

    @Test
    void anEntryAnotherServiceReportsKeepsItsOwnActor() {
        UUID target = UUID.randomUUID();

        service.record(new AuditEntryRequest(AuditAction.SETTLEMENT_RUN_TRIGGERED, AuditActorType.PLATFORM_ADMIN,
                "platform-admin", target, "MERCHANT", target.toString(), Map.of("scope", "ONE_MERCHANT"), "198.51.100.1"));

        AuditLog entry = saved();
        assertThat(entry.getActorType()).isEqualTo(AuditActorType.PLATFORM_ADMIN);
        assertThat(entry.getClientIp()).isEqualTo("198.51.100.1");
        assertThat(entry.getMerchantId()).isEqualTo(target);
    }

    @Test
    void overlongFieldsAreCutSoAnEntryNeverFailsToSave() {
        service.record(new AuditEntryRequest(AuditAction.USER_ADDED, AuditActorType.USER, "a".repeat(400), merchantId,
                "t".repeat(100), "i".repeat(300), null, "9".repeat(100)));

        AuditLog entry = saved();
        assertThat(entry.getActor()).hasSize(255);
        assertThat(entry.getTargetType()).hasSize(40);
        assertThat(entry.getTargetId()).hasSize(100);
        assertThat(entry.getClientIp()).hasSize(64);
    }

    // ---- reading

    private static SliceImpl<AuditLog> noEntries() {
        return new SliceImpl<>(List.of(), PageRequest.of(0, 20), false);
    }

    @Test
    void theListUsesTheQueryThatMatchesTheFiltersGiven() {
        when(repository.findAllByOrderByOccurredAtDesc(any(Pageable.class))).thenReturn(noEntries());
        when(repository.findByMerchantIdOrderByOccurredAtDesc(any(), any(Pageable.class))).thenReturn(noEntries());
        when(repository.findByActionOrderByOccurredAtDesc(any(), any(Pageable.class))).thenReturn(noEntries());
        when(repository.findByMerchantIdAndActionOrderByOccurredAtDesc(any(), any(), any(Pageable.class))).thenReturn(noEntries());

        service.list(null, null, 0, 20);
        service.list(merchantId, null, 0, 20);
        service.list(null, AuditAction.USER_ADDED, 0, 20);
        service.list(merchantId, AuditAction.USER_ADDED, 0, 20);

        verify(repository).findAllByOrderByOccurredAtDesc(any(Pageable.class));
        verify(repository).findByMerchantIdOrderByOccurredAtDesc(any(), any(Pageable.class));
        verify(repository).findByActionOrderByOccurredAtDesc(any(), any(Pageable.class));
        verify(repository).findByMerchantIdAndActionOrderByOccurredAtDesc(any(), any(), any(Pageable.class));
    }

    @Test
    void thePageSizeAndDepthAreCapped() {
        when(repository.findByMerchantIdOrderByOccurredAtDesc(any(), any(Pageable.class))).thenReturn(noEntries());

        service.list(merchantId, null, 999_999, 5_000);

        ArgumentCaptor<Pageable> paging = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByMerchantIdOrderByOccurredAtDesc(any(), paging.capture());
        assertThat(paging.getValue().getPageSize()).isEqualTo(100);
        assertThat(paging.getValue().getPageNumber()).isEqualTo(1000);
        verify(repository, never()).save(any());
    }
}
