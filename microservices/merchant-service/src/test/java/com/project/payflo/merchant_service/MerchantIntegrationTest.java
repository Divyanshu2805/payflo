package com.project.payflo.merchant_service;

import com.jayway.jsonpath.JsonPath;
import com.project.payflo.test_support.PayfloIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Merchant accounts, sign-in and credentials on a real database and Redis: how a password and an API-key secret are
 * stored, that a secret is shown once, and the lifecycle of a refresh token.
 */
@AutoConfigureMockMvc
class MerchantIntegrationTest extends PayfloIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    @Autowired
    private com.project.payflo.merchant_service.service.AuditLogService auditLogService;

    private static final String PASSWORD = "Correct-Horse-Battery-1";

    private static String json(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private static String field(MvcResult r, String path) throws Exception {
        return JsonPath.read(json(r), path).toString();
    }

    private MvcResult signup(String email) throws Exception {
        return mvc.perform(post("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(
                "{\"name\":\"Asha\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"businessName\":\"Asha Stores\",\"businessType\":\"PROPRIETORSHIP\"}")).andReturn();
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    // what the gateway adds once it has authenticated a dashboard login
    private MockHttpServletRequestBuilder asOwner(MockHttpServletRequestBuilder request, String merchantId) {
        return request.header("X-Merchant-Id", merchantId).header("X-User-Role", "OWNER").contentType(MediaType.APPLICATION_JSON);
    }

    // a dashboard login: the gateway adds the user's email as well as their role
    private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request, String merchantId, String email, String role) {
        return request.header("X-Merchant-Id", merchantId).header("X-User-Role", role).header("X-User-Email", email)
                .header("X-Client-Ip", "203.0.113.7").contentType(MediaType.APPLICATION_JSON);
    }

    // the platform operator, as the gateway marks a request once it has checked the admin key
    private MockHttpServletRequestBuilder asOperator(MockHttpServletRequestBuilder request) {
        return request.header("X-Platform-Admin", "true").header("X-Client-Ip", "198.51.100.5").contentType(MediaType.APPLICATION_JSON);
    }

    private static String newEmail() {
        return "it-" + UUID.randomUUID() + "@payflo.test";
    }

    private int auditRows(String merchantId, String action) {
        return jdbc.queryForObject("select count(*) from audit_log where merchant_id = ?::uuid and action = ?", Integer.class, merchantId, action);
    }

    // ---- accounts and passwords

    @Test
    void aNewMerchantStartsPendingKycAndItsPasswordIsStoredOnlyAsABcryptHash() throws Exception {
        String email = newEmail();

        MvcResult created = signup(email);

        assertThat(created.getResponse().getStatus()).as(json(created)).isEqualTo(201);
        assertThat(field(created, "$.merchantStatus")).isEqualTo("PENDING_KYC");
        assertThat(json(created)).doesNotContain(PASSWORD);
        String stored = jdbc.queryForObject("select password_hash from app_user where email = ?", String.class, email);
        assertThat(stored).startsWith("$2").doesNotContain(PASSWORD);
    }

    @Test
    void signingUpWithARegisteredEmailLooksLikeASuccessSoTheEndpointCannotBeUsedToFindWhichEmailsExist() throws Exception {
        String email = newEmail();
        MvcResult first = signup(email);

        MvcResult again = mvc.perform(post("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(
                "{\"name\":\"Someone Else\",\"email\":\"" + email + "\",\"password\":\"An-Attackers-Password-9\"}")).andReturn();

        // the same status and the same shape as a signup that worked, not a 409 that names the email as taken
        assertThat(again.getResponse().getStatus()).isEqualTo(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(field(again, "$.merchantStatus")).isEqualTo("PENDING_KYC");
        assertThat(field(again, "$.id")).isNotEqualTo(field(first, "$.id"));
        assertThat(json(again)).doesNotContain("DUPLICATE").doesNotContain("already");
        // but nothing was created or changed: still one merchant, and the original password is the one that works
        assertThat(jdbc.queryForObject("select count(*) from merchant where email = ?", Integer.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from app_user where email = ?", Integer.class, email)).isEqualTo(1);
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(login(email, "An-Attackers-Password-9").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aTeamMembersEmailIsNotRevealedBySigningUpWithItEither() throws Exception {
        String ownerEmail = newEmail();
        String merchantId = field(signup(ownerEmail), "$.id");
        String teamEmail = newEmail();
        mvc.perform(asUser(post("/v1/merchants/users"), merchantId, ownerEmail, "OWNER")
                .content("{\"email\":\"" + teamEmail + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"TEAM\"}")).andReturn();

        MvcResult attempt = signup(teamEmail);

        assertThat(attempt.getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from merchant where email = ?", Integer.class, teamEmail)).isZero();
    }

    @Test
    void twoSignupsForOneEmailAtTheSameInstantCreateOneAccountAndAnswerBothTheSameWay() throws Exception {
        String email = newEmail();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<MvcResult>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return signup(email);
                }));
            }
            start.countDown();
            for (var call : calls) {
                assertThat(call.get().getResponse().getStatus()).as(json(call.get())).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from merchant where email = ?", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void aWrongPasswordAndAnUnknownEmailGetTheSameAnswer() throws Exception {
        String email = newEmail();
        signup(email);

        MvcResult wrong = login(email, "not-the-password");
        MvcResult unknown = login(newEmail(), "not-the-password");

        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(field(wrong, "$.errorCode")).isEqualTo(field(unknown, "$.errorCode")); // doesn't reveal which emails exist
    }

    @Test
    void repeatedWrongPasswordsLockTheAccountOutEvenForTheRightPassword() throws Exception {
        String email = newEmail();
        signup(email);

        int lastStatus = 0;
        for (int i = 0; i < 12 && lastStatus != 429; i++) {
            lastStatus = login(email, "wrong-password-" + i).getResponse().getStatus();
        }

        assertThat(lastStatus).isEqualTo(429);
        assertThat(login(email, PASSWORD).getResponse().getStatus()).as("the lockout holds against the correct password too").isEqualTo(429);
    }

    // ---- tokens

    @Test
    void aRefreshTokenWorksOnceAndTheNextOneReplacesIt() throws Exception {
        String email = newEmail();
        signup(email);
        MvcResult first = login(email, PASSWORD);
        String refresh = field(first, "$.refreshToken");

        MvcResult renewed = mvc.perform(post("/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refresh + "\"}")).andReturn();
        MvcResult reused = mvc.perform(post("/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refresh + "\"}")).andReturn();

        assertThat(renewed.getResponse().getStatus()).as(json(renewed)).isEqualTo(200);
        assertThat(field(renewed, "$.refreshToken")).isNotEqualTo(refresh);
        assertThat(reused.getResponse().getStatus()).isEqualTo(401);
    }

    // ---- API keys and webhook secrets

    @Test
    void anApiKeySecretIsShownOnceAndOnlyItsHashIsStored() throws Exception {
        String email = newEmail();
        String merchantId = field(signup(email), "$.id");

        MvcResult created = mvc.perform(asOwner(post("/v1/merchants/api-keys"), merchantId).content("{\"environment\":\"TEST\"}")).andReturn();

        assertThat(created.getResponse().getStatus()).as(json(created)).isEqualTo(201);
        String keyId = field(created, "$.keyId");
        String secret = field(created, "$.keySecret");
        assertThat(keyId).startsWith("pf_test_");
        String stored = jdbc.queryForObject("select key_secret_hash from api_key where key_id = ?", String.class, keyId);
        assertThat(stored).startsWith("$2").doesNotContain(secret);

        // listing never includes a secret
        MvcResult list = mvc.perform(asOwner(get("/v1/merchants/api-keys"), merchantId)).andReturn();
        assertThat(json(list)).contains(keyId).doesNotContain(secret).doesNotContain("keySecret");
    }

    @Test
    void aRotatedApiKeyKeepsTheOldSecretOnlyForTheGracePeriod() throws Exception {
        String merchantId = field(signup(newEmail()), "$.id");
        MvcResult created = mvc.perform(asOwner(post("/v1/merchants/api-keys"), merchantId).content("{\"environment\":\"TEST\"}")).andReturn();
        String id = field(created, "$.id");
        String oldHash = jdbc.queryForObject("select key_secret_hash from api_key where id = ?::uuid", String.class, id);

        MvcResult rotated = mvc.perform(asOwner(post("/v1/merchants/api-keys/" + id + "/rotate"), merchantId).content("{\"gracePeriodHours\":24}")).andReturn();

        assertThat(rotated.getResponse().getStatus()).as(json(rotated)).isEqualTo(200);
        assertThat(field(rotated, "$.keySecret")).isNotEqualTo(field(created, "$.keySecret"));
        assertThat(jdbc.queryForObject("select previous_key_secret_hash from api_key where id = ?::uuid", String.class, id)).isEqualTo(oldHash);
        assertThat(jdbc.queryForObject("select grace_period_expires_at is not null from api_key where id = ?::uuid", Boolean.class, id)).isTrue();
    }

    @Test
    void aWebhookSecretIsShownOnceAndStoredEncryptedNotInTheClear() throws Exception {
        String merchantId = field(signup(newEmail()), "$.id");

        MvcResult created = mvc.perform(asOwner(post("/v1/merchants/webhooks"), merchantId).content("{\"targetUrl\":\"http://localhost:8080/webhook/success\"}")).andReturn();

        assertThat(created.getResponse().getStatus()).as(json(created)).isEqualTo(200);
        String secret = field(created, "$.webhookSecret");
        String stored = jdbc.queryForObject("select webhook_secret from merchant_webhook_config where id = ?::uuid", String.class, field(created, "$.id"));
        assertThat(stored).isNotBlank().isNotEqualTo(secret).doesNotContain(secret);

        MvcResult read = mvc.perform(asOwner(get("/v1/merchants/webhooks/" + field(created, "$.id")), merchantId)).andReturn();
        assertThat(json(read)).doesNotContain(secret);
    }

    @Test
    void aWebhookToAnInternalMetadataAddressIsRefusedAtConfigurationTime() throws Exception {
        String merchantId = field(signup(newEmail()), "$.id");

        MvcResult refused = mvc.perform(asOwner(post("/v1/merchants/webhooks"), merchantId)
                .content("{\"targetUrl\":\"http://169.254.169.254/latest/meta-data\"}")).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(refused, "$.errorCode")).isEqualTo("WEBHOOK_URL_NOT_ALLOWED");
    }

    // ---- the audit log

    @Test
    void sensitiveActionsLeaveAuditEntriesWithWhoAndFromWhereAndNeverASecret() throws Exception {
        String ownerEmail = newEmail();
        String merchantId = field(signup(ownerEmail), "$.id");

        MvcResult key = mvc.perform(asUser(post("/v1/merchants/api-keys"), merchantId, ownerEmail, "OWNER").content("{\"environment\":\"TEST\"}")).andReturn();
        String keyRowId = field(key, "$.id");
        String keySecret = field(key, "$.keySecret");
        mvc.perform(asUser(post("/v1/merchants/api-keys/" + keyRowId + "/rotate"), merchantId, ownerEmail, "OWNER").content("{\"gracePeriodHours\":1}")).andReturn();
        mvc.perform(asUser(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/merchants/api-keys/" + keyRowId),
                merchantId, ownerEmail, "OWNER")).andReturn();
        MvcResult webhook = mvc.perform(asUser(post("/v1/merchants/webhooks"), merchantId, ownerEmail, "OWNER")
                .content("{\"targetUrl\":\"http://localhost:8080/webhook/success?token=abc123\"}")).andReturn();
        String webhookSecret = field(webhook, "$.webhookSecret");
        mvc.perform(asUser(post("/v1/merchants/webhooks/" + field(webhook, "$.id") + "/rotate-secret"), merchantId, ownerEmail, "OWNER")).andReturn();
        mvc.perform(asUser(post("/v1/merchants/users"), merchantId, ownerEmail, "OWNER")
                .content("{\"email\":\"" + newEmail() + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"TEAM\"}")).andReturn();
        mvc.perform(asUser(put("/v1/merchants/me/settlement-bank"), merchantId, ownerEmail, "OWNER")
                .content("{\"accountNumber\":\"123456789012\",\"ifsc\":\"HDFC0001234\",\"accountHolderName\":\"Asha\",\"currentPassword\":\"" + PASSWORD + "\"}")).andReturn();

        for (String action : new String[]{"API_KEY_CREATED", "API_KEY_ROTATED", "API_KEY_REVOKED", "WEBHOOK_CONFIG_CREATED",
                "WEBHOOK_SECRET_ROTATED", "USER_ADDED", "SETTLEMENT_BANK_CHANGED"}) {
            assertThat(auditRows(merchantId, action)).as(action).isEqualTo(1);
        }
        // who, from where, and when
        var row = jdbc.queryForMap("select * from audit_log where merchant_id = ?::uuid and action = 'SETTLEMENT_BANK_CHANGED'", merchantId);
        assertThat(row.get("actor_type")).isEqualTo("USER");
        assertThat(row.get("actor")).isEqualTo(ownerEmail);
        assertThat(row.get("client_ip")).isEqualTo("203.0.113.7");
        assertThat(row.get("occurred_at")).isNotNull();
        // never a secret, a full account number, or a URL's token
        String everything = jdbc.queryForList("select details::text from audit_log where merchant_id = ?::uuid", String.class, merchantId).toString();
        assertThat(everything).doesNotContain(keySecret, webhookSecret, "123456789012", "abc123", PASSWORD).contains("XXXXXXXX9012");
    }

    @Test
    void aChangeThatIsRefusedLeavesNoEntry() throws Exception {
        String ownerEmail = newEmail();
        String merchantId = field(signup(ownerEmail), "$.id");

        MvcResult wrongPassword = mvc.perform(asUser(put("/v1/merchants/me/settlement-bank"), merchantId, ownerEmail, "OWNER")
                .content("{\"accountNumber\":\"123456789012\",\"ifsc\":\"HDFC0001234\",\"accountHolderName\":\"Asha\",\"currentPassword\":\"nope-nope-nope\"}")).andReturn();
        MvcResult teamMember = mvc.perform(asUser(post("/v1/merchants/users"), merchantId, "t@example.com", "TEAM")
                .content("{\"email\":\"" + newEmail() + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"TEAM\"}")).andReturn();

        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(403);
        assertThat(teamMember.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where merchant_id = ?::uuid", Integer.class, merchantId)).isZero();
    }

    @Test
    void aMerchantReadsItsOwnAuditLogNewestFirstAndNoOnesElse() throws Exception {
        String ownerEmail = newEmail();
        String merchantId = field(signup(ownerEmail), "$.id");
        String otherEmail = newEmail();
        String otherId = field(signup(otherEmail), "$.id");
        mvc.perform(asUser(post("/v1/merchants/api-keys"), merchantId, ownerEmail, "OWNER").content("{\"environment\":\"TEST\"}")).andReturn();
        mvc.perform(asUser(post("/v1/merchants/webhooks"), merchantId, ownerEmail, "OWNER")
                .content("{\"targetUrl\":\"http://localhost:8080/webhook/success\"}")).andReturn();
        mvc.perform(asUser(post("/v1/merchants/api-keys"), otherId, otherEmail, "OWNER").content("{\"environment\":\"TEST\"}")).andReturn();

        MvcResult mine = mvc.perform(asUser(get("/v1/merchants/audit-log"), merchantId, ownerEmail, "OWNER")).andReturn();
        MvcResult filtered = mvc.perform(asUser(get("/v1/merchants/audit-log?action=API_KEY_CREATED"), merchantId, ownerEmail, "ADMIN")).andReturn();

        assertThat(mine.getResponse().getStatus()).as(json(mine)).isEqualTo(200);
        assertThat(field(mine, "$.items[0].action")).isEqualTo("WEBHOOK_CONFIG_CREATED"); // newest first
        assertThat(field(mine, "$.items[1].action")).isEqualTo("API_KEY_CREATED");
        assertThat(JsonPath.<java.util.List<?>>read(json(mine), "$.items")).hasSize(2);
        assertThat(json(mine)).doesNotContain(otherId).doesNotContain(otherEmail);
        assertThat(JsonPath.<java.util.List<?>>read(json(filtered), "$.items")).hasSize(1);
    }

    @Test
    void onlyAnOwnerOrAdminLoggedInToTheDashboardMayReadTheAuditLog() throws Exception {
        String ownerEmail = newEmail();
        String merchantId = field(signup(ownerEmail), "$.id");

        MvcResult team = mvc.perform(asUser(get("/v1/merchants/audit-log"), merchantId, "team@example.com", "TEAM")).andReturn();
        MvcResult apiKey = mvc.perform(get("/v1/merchants/audit-log").header("X-Merchant-Id", merchantId).header("X-Key-Id", "pf_live_abc")).andReturn();

        assertThat(team.getResponse().getStatus()).isEqualTo(403);
        assertThat(apiKey.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void theAuditLogCannotBeChangedOrEmptiedEvenByTheApplicationsOwnDatabaseUser() throws Exception {
        String ownerEmail = newEmail();
        String merchantId = field(signup(ownerEmail), "$.id");
        mvc.perform(asUser(post("/v1/merchants/api-keys"), merchantId, ownerEmail, "OWNER").content("{\"environment\":\"TEST\"}")).andReturn();
        assertThat(auditRows(merchantId, "API_KEY_CREATED")).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("update audit_log set actor = 'someone-else' where merchant_id = ?::uuid", merchantId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_log where merchant_id = ?::uuid", merchantId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("truncate table audit_log")).hasMessageContaining("append-only");

        assertThat(auditRows(merchantId, "API_KEY_CREATED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select actor from audit_log where merchant_id = ?::uuid", String.class, merchantId)).isEqualTo(ownerEmail);
    }

    @Test
    void everyActionTheCodeCanRecordIsAcceptedByTheDatabasesCheckConstraint() {
        UUID merchant = UUID.randomUUID();
        for (com.project.payflo.common_lib.enums.AuditAction action : com.project.payflo.common_lib.enums.AuditAction.values()) {
            for (com.project.payflo.common_lib.enums.AuditActorType actor : com.project.payflo.common_lib.enums.AuditActorType.values()) {
                auditLogService.record(new com.project.payflo.common_lib.dto.AuditEntryRequest(action, actor, "who", merchant,
                        null, null, null, null));
            }
        }

        assertThat(jdbc.queryForObject("select count(*) from audit_log where merchant_id = ?::uuid", Integer.class, merchant.toString()))
                .isEqualTo(com.project.payflo.common_lib.enums.AuditAction.values().length
                        * com.project.payflo.common_lib.enums.AuditActorType.values().length);
    }

    @Test
    void anotherServiceCanRecordAnEntryOnlyWithTheInternalToken() throws Exception {
        String merchantId = field(signup(newEmail()), "$.id");
        String entry = "{\"action\":\"SETTLEMENT_RUN_TRIGGERED\",\"actorType\":\"PLATFORM_ADMIN\",\"actor\":\"platform-admin\","
                + "\"merchantId\":\"" + merchantId + "\",\"targetType\":\"MERCHANT\",\"targetId\":\"" + merchantId + "\","
                + "\"details\":{\"scope\":\"ONE_MERCHANT\"},\"clientIp\":\"198.51.100.5\"}";

        MvcResult without = mvc.perform(post("/internal/audit").contentType(MediaType.APPLICATION_JSON).content(entry)).andReturn();
        MvcResult with = mvc.perform(post("/internal/audit").contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Token", "dev-internal-api-token-change-me").content(entry)).andReturn();

        assertThat(without.getResponse().getStatus()).isEqualTo(401);
        assertThat(with.getResponse().getStatus()).isEqualTo(204);
        assertThat(auditRows(merchantId, "SETTLEMENT_RUN_TRIGGERED")).isEqualTo(1);
    }

    // ---- the admin API

    @Test
    void theAdminApiRefusesAnythingTheGatewayHasNotMarkedAsTheOperator() throws Exception {
        String ownerEmail = newEmail();
        String merchantId = field(signup(ownerEmail), "$.id");

        MvcResult asMerchant = mvc.perform(asUser(post("/v1/admin/merchants/" + merchantId + "/suspend"), merchantId, ownerEmail, "OWNER")
                .content("{\"reason\":\"let me suspend myself\"}")).andReturn();
        MvcResult listing = mvc.perform(get("/v1/admin/merchants")).andReturn();

        assertThat(asMerchant.getResponse().getStatus()).isEqualTo(403);
        assertThat(field(asMerchant, "$.errorCode")).isEqualTo("ADMIN_REQUIRED");
        assertThat(listing.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select status from merchant where id = ?::uuid", String.class, merchantId)).isEqualTo("PENDING_KYC");
    }

    @Test
    void theOperatorSuspendsAMerchantAtOnceAndReactivatingRestoresWhatItWas() throws Exception {
        String email = newEmail();
        String merchantId = field(signup(email), "$.id");

        MvcResult suspended = mvc.perform(asOperator(post("/v1/admin/merchants/" + merchantId + "/suspend")).content("{\"reason\":\"chargeback ring\"}")).andReturn();

        assertThat(suspended.getResponse().getStatus()).as(json(suspended)).isEqualTo(200);
        assertThat(field(suspended, "$.status")).isEqualTo("SUSPENDED");
        assertThat(field(suspended, "$.suspensionReason")).isEqualTo("chargeback ring");
        // the gateway's cache is written now, not a minute from now, and the merchant can no longer log in
        assertThat(redis.opsForValue().get("merchant:status:" + merchantId)).isEqualTo("SUSPENDED");
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(403);
        // audited, with the operator as the actor and the address the gateway saw
        var audit = jdbc.queryForMap("select * from audit_log where merchant_id = ?::uuid and action = 'MERCHANT_SUSPENDED'", merchantId);
        assertThat(audit.get("actor_type")).isEqualTo("PLATFORM_ADMIN");
        assertThat(audit.get("client_ip")).isEqualTo("198.51.100.5");
        assertThat(audit.get("details").toString()).contains("chargeback ring");

        MvcResult again = mvc.perform(asOperator(post("/v1/admin/merchants/" + merchantId + "/suspend")).content("{\"reason\":\"twice\"}")).andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(again, "$.errorCode")).isEqualTo("MERCHANT_ALREADY_SUSPENDED");

        MvcResult reactivated = mvc.perform(asOperator(post("/v1/admin/merchants/" + merchantId + "/reactivate")).content("{\"reason\":\"cleared\"}")).andReturn();

        assertThat(reactivated.getResponse().getStatus()).as(json(reactivated)).isEqualTo(200);
        // it hadn't passed KYC when suspended, so it goes back to waiting for KYC, not to ACTIVE
        assertThat(field(reactivated, "$.status")).isEqualTo("PENDING_KYC");
        assertThat(redis.opsForValue().get("merchant:status:" + merchantId)).isEqualTo("PENDING_KYC");
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(auditRows(merchantId, "MERCHANT_REACTIVATED")).isEqualTo(1);
    }

    @Test
    void aSuspensionNeedsAReasonAndAnUnknownMerchantIsA404() throws Exception {
        String merchantId = field(signup(newEmail()), "$.id");

        MvcResult noReason = mvc.perform(asOperator(post("/v1/admin/merchants/" + merchantId + "/suspend")).content("{}")).andReturn();
        MvcResult unknown = mvc.perform(asOperator(post("/v1/admin/merchants/" + UUID.randomUUID() + "/suspend")).content("{\"reason\":\"x\"}")).andReturn();

        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select status from merchant where id = ?::uuid", String.class, merchantId)).isEqualTo("PENDING_KYC");
    }

    @Test
    void theOperatorListsMerchantsAndTheAuditLogAcrossAllOfThem() throws Exception {
        String merchantId = field(signup(newEmail()), "$.id");
        mvc.perform(asOperator(post("/v1/admin/merchants/" + merchantId + "/suspend")).content("{\"reason\":\"fraud\"}")).andReturn();

        MvcResult suspendedOnes = mvc.perform(asOperator(get("/v1/admin/merchants?status=SUSPENDED&size=100"))).andReturn();
        MvcResult one = mvc.perform(asOperator(get("/v1/admin/merchants/" + merchantId))).andReturn();
        MvcResult log = mvc.perform(asOperator(get("/v1/admin/audit-log?merchantId=" + merchantId + "&action=MERCHANT_SUSPENDED"))).andReturn();

        assertThat(json(suspendedOnes)).contains(merchantId);
        assertThat(field(one, "$.status")).isEqualTo("SUSPENDED");
        assertThat(json(one)).doesNotContain("settlementBank").doesNotContain("panId");
        assertThat(JsonPath.<java.util.List<?>>read(json(log), "$.items")).hasSize(1);
    }

    @Test
    void oneMerchantCannotReadOrRevokeAnotherMerchantsApiKey() throws Exception {
        String ownerId = field(signup(newEmail()), "$.id");
        String strangerId = field(signup(newEmail()), "$.id");
        String keyRowId = field(mvc.perform(asOwner(post("/v1/merchants/api-keys"), ownerId).content("{\"environment\":\"TEST\"}")).andReturn(), "$.id");

        MvcResult revoke = mvc.perform(asOwner(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/merchants/api-keys/" + keyRowId), strangerId)).andReturn();

        assertThat(revoke.getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select enabled from api_key where id = ?::uuid", Boolean.class, keyRowId)).isTrue();
    }
}
