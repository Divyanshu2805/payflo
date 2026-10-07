package com.project.payflo.operations_service;

import com.jayway.jsonpath.JsonPath;
import com.project.payflo.common_lib.dto.AuditEntryRequest;
import com.project.payflo.common_lib.dto.PaymentSettlementView;
import com.project.payflo.common_lib.dto.SettlementBankDetails;
import com.project.payflo.common_lib.enums.AuditAction;
import com.project.payflo.common_lib.enums.AuditActorType;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The platform operator's settlement run, through the HTTP API on a real database and Redis lock. The operator is the
 * gateway's say-so (the {@code X-Platform-Admin} header); merchant-service (the audit log, the active merchants and the
 * bank details) and payment-service are stubs.
 */
class AdminSettlementIntegrationTest extends OperationsIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private LockProvider lockProvider;

    private final UUID merchant = UUID.randomUUID();

    @BeforeEach
    void anActiveMerchantWithACapturedPayment() {
        when(merchantClient.listActiveMerchantIds()).thenReturn(List.of(merchant));
        when(merchantClient.getSettlementBankDetails(merchant)).thenReturn(new SettlementBankDetails("123456789012", "HDFC0001234", "Holder"));
        when(paymentClient.findUnsettledCaptured(eq(merchant), any(), eq(0), anyInt()))
                .thenReturn(List.of(new PaymentSettlementView(UUID.randomUUID(), 80_000, 0, "INR")));
    }

    private MockHttpServletRequestBuilder asOperator(String body) {
        return post("/v1/admin/settlements/run").header("X-Platform-Admin", "true").header("X-Client-Ip", "198.51.100.5")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private int settlementsOf(UUID merchantId) {
        return jdbc.queryForObject("select count(*) from settlement where merchant_id = ?", Integer.class, merchantId);
    }

    @Test
    void theOperatorRunsEveryActiveMerchantAndTheRunIsAuditedFirst() throws Exception {
        MvcResult result = mvc.perform(asOperator("")).andReturn();

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String json = result.getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(json, "$.merchants")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(json, "$.failedMerchants")).isZero();
        assertThat(JsonPath.<Integer>read(json, "$.settlementsCreated")).isEqualTo(1);
        assertThat(settlementsOf(merchant)).isEqualTo(1);

        ArgumentCaptor<AuditEntryRequest> entry = ArgumentCaptor.forClass(AuditEntryRequest.class);
        verify(auditClient).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.SETTLEMENT_RUN_TRIGGERED);
        assertThat(entry.getValue().actorType()).isEqualTo(AuditActorType.PLATFORM_ADMIN);
        assertThat(entry.getValue().clientIp()).isEqualTo("198.51.100.5");
        assertThat(entry.getValue().details()).containsEntry("scope", "ALL_ACTIVE_MERCHANTS");

        // the payout then goes through the simulated bank like any other
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select status from settlement where merchant_id = ?", String.class, merchant)).isEqualTo("PROCESSED"));
    }

    @Test
    void theOperatorCanSettleJustOneMerchant() throws Exception {
        UUID other = UUID.randomUUID();
        when(merchantClient.listActiveMerchantIds()).thenReturn(List.of(merchant, other));

        MvcResult result = mvc.perform(asOperator("{\"merchantId\":\"" + merchant + "\"}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(result.getResponse().getContentAsString(), "$.merchants")).isEqualTo(1);
        assertThat(settlementsOf(merchant)).isEqualTo(1);
        assertThat(settlementsOf(other)).isZero();
        ArgumentCaptor<AuditEntryRequest> entry = ArgumentCaptor.forClass(AuditEntryRequest.class);
        verify(auditClient).record(entry.capture());
        assertThat(entry.getValue().merchantId()).isEqualTo(merchant);
        assertThat(entry.getValue().details()).containsEntry("scope", "ONE_MERCHANT");
    }

    @Test
    void aMerchantThatIsNotActiveCannotBeSettledAndNothingIsAudited() throws Exception {
        UUID suspended = UUID.randomUUID(); // not among the active merchants

        MvcResult result = mvc.perform(asOperator("{\"merchantId\":\"" + suspended + "\"}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.errorCode")).isEqualTo("MERCHANT_NOT_ACTIVE");
        verify(auditClient, never()).record(any());
        assertThat(settlementsOf(suspended)).isZero();
    }

    @Test
    void ifTheAuditLogCannotBeWrittenNothingRuns() throws Exception {
        doThrow(new RuntimeException("merchant-service is down")).when(auditClient).record(any());

        MvcResult result = mvc.perform(asOperator("")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.errorCode")).isEqualTo("AUDIT_LOG_UNAVAILABLE");
        assertThat(settlementsOf(merchant)).as("no unaudited admin action").isZero();
    }

    @Test
    void aRunWhileAnotherIsInProgressIsAConflictAndOneAfterItIsFine() throws Exception {
        // the nightly job's lock, as it would be held while it runs
        SimpleLock held = lockProvider.lock(new LockConfiguration(Instant.now(), "operations-service-settlement-engine",
                Duration.ofMinutes(5), Duration.ZERO)).orElseThrow();
        try {
            MvcResult busy = mvc.perform(asOperator("")).andReturn();

            assertThat(busy.getResponse().getStatus()).isEqualTo(409);
            assertThat(JsonPath.<String>read(busy.getResponse().getContentAsString(), "$.errorCode")).isEqualTo("SETTLEMENT_RUN_IN_PROGRESS");
            assertThat(settlementsOf(merchant)).isZero();
        } finally {
            held.unlock();
        }

        assertThat(mvc.perform(asOperator("")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(settlementsOf(merchant)).isEqualTo(1);
    }

    @Test
    void theLockIsReleasedAfterARunSoTheNextOneIsNotBlocked() throws Exception {
        assertThat(mvc.perform(asOperator("")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(asOperator("")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void withoutTheGatewaysAdminMarkerNothingRuns() throws Exception {
        MvcResult asMerchant = mvc.perform(post("/v1/admin/settlements/run").header("X-Merchant-Id", merchant.toString())
                .header("X-User-Role", "OWNER").contentType(MediaType.APPLICATION_JSON)).andReturn();

        assertThat(asMerchant.getResponse().getStatus()).isEqualTo(403);
        assertThat(JsonPath.<String>read(asMerchant.getResponse().getContentAsString(), "$.errorCode")).isEqualTo("ADMIN_REQUIRED");
        verify(auditClient, never()).record(any());
        assertThat(settlementsOf(merchant)).isZero();
    }
}
