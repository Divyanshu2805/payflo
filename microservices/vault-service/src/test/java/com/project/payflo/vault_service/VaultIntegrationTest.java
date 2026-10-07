package com.project.payflo.vault_service;

import com.jayway.jsonpath.JsonPath;
import com.project.payflo.test_support.PayfloIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The card vault on a real database: a card number goes in, a token comes out, the number is encrypted at rest, and
 * only the merchant that created a token can charge it.
 */
@AutoConfigureMockMvc
class VaultIntegrationTest extends PayfloIntegrationTest {

    private static final String INTERNAL_TOKEN = "dev-internal-api-token-change-me"; // config-repo's development default
    private static final String VISA = "4111111111111111";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID merchant = UUID.randomUUID();

    private MvcResult tokenize(UUID merchantId, String pan) throws Exception {
        return mvc.perform(post("/v1/vault/tokenize").header("X-Merchant-Id", merchantId.toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pan\":\"" + pan + "\",\"cvv\":\"737\",\"expiryMonth\":12,\"expiryYear\":2031,\"cardHolderName\":\"Asha Rao\"}")).andReturn();
    }

    private String token(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private MvcResult charge(UUID merchantId, String token, String internalToken) throws Exception {
        var request = post("/internal/vault/charge").contentType(MediaType.APPLICATION_JSON).content(
                "{\"paymentId\":\"" + UUID.randomUUID() + "\",\"merchantId\":\"" + merchantId + "\",\"token\":\"" + token
                        + "\",\"amount\":{\"amountUnits\":5000,\"currency\":\"INR\"},\"methodDetails\":{}}");
        if (internalToken != null) {
            request.header("X-Internal-Token", internalToken);
        }
        return mvc.perform(request).andReturn();
    }

    @Test
    void aMerchantTokenizingCardsFasterThanAnyCheckoutIsRefusedUntilTheWindowEndsAndOthersAreUnaffected() throws Exception {
        UUID bot = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();

        for (int i = 1; i <= 30; i++) {
            assertThat(tokenize(bot, VISA).getResponse().getStatus()).as("card " + i).isEqualTo(201);
        }
        MvcResult refused = tokenize(bot, VISA);

        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(JsonPath.<String>read(refused.getResponse().getContentAsString(), "$.errorCode")).isEqualTo("CARD_TOKENIZATION_LIMIT_EXCEEDED");
        assertThat(Integer.parseInt(refused.getResponse().getHeader("Retry-After"))).isBetween(1, 60);
        // the refused attempt was stopped before anything was encrypted or stored
        assertThat(jdbc.queryForObject("select count(*) from card_token where merchant = ?::uuid", Integer.class, bot.toString())).isEqualTo(30);
        // another merchant, even tokenizing the very same card, is not touched
        assertThat(tokenize(bystander, VISA).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void aCardIsTokenizedAndTheResponseNeverEchoesTheNumberOrTheCvv() throws Exception {
        MvcResult result = tokenize(merchant, VISA);

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"lastFour\":\"1111\"").contains("\"brand\":\"VISA\"");
        assertThat(body).doesNotContain(VISA).doesNotContain("737");
        assertThat(token(result)).startsWith("tok_");
    }

    @Test
    void theCardNumberIsEncryptedAtRestAndTheCvvIsNotStoredAnywhere() throws Exception {
        String token = token(tokenize(merchant, VISA));

        byte[] encrypted = jdbc.queryForObject("select c.encrypted_pan from vault_card c join card_token t on t.vault_card_id = c.id where t.token = ?",
                byte[].class, token);

        assertThat(new String(encrypted, StandardCharsets.ISO_8859_1)).doesNotContain(VISA);
        assertThat(jdbc.queryForObject("select last_four from vault_card c join card_token t on t.vault_card_id = c.id where t.token = ?", String.class, token))
                .isEqualTo("1111");
        // no column of either table holds the CVV or the full number, in any form
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name in ('vault_card', 'card_token')", String.class))
                .noneMatch(column -> column.contains("cvv") || column.equals("pan"));
    }

    @Test
    void anInvalidCardNumberIsRefusedAndNothingIsStored() throws Exception {
        Integer before = jdbc.queryForObject("select count(*) from vault_card", Integer.class);

        MvcResult result = tokenize(merchant, "4111111111111112"); // fails the Luhn check

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from vault_card", Integer.class)).isEqualTo(before);
    }

    @Test
    void theOwnerOfATokenCanChargeItAndTheCardNumberIsNotInTheAnswer() throws Exception {
        String token = token(tokenize(merchant, VISA));

        MvcResult result = charge(merchant, token, INTERNAL_TOKEN);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("PENDING");
        assertThat(body).doesNotContain(VISA);
    }

    @Test
    void theTestDeclineNumberIsDeclinedWithoutRevealingAnythingElse() throws Exception {
        String token = token(tokenize(merchant, "4000000000000002"));

        MvcResult result = charge(merchant, token, INTERNAL_TOKEN);

        assertThat(result.getResponse().getContentAsString()).contains("FAILURE").contains("CARD_DECLINED").doesNotContain("4000000000000002");
    }

    @Test
    void anotherMerchantCannotChargeATokenItDidNotCreate() throws Exception {
        String token = token(tokenize(merchant, VISA));

        MvcResult result = charge(UUID.randomUUID(), token, INTERNAL_TOKEN);

        // the same answer as for a token that doesn't exist: nothing says the token is real but someone else's
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("CARDTOKEN_NOT_FOUND").doesNotContain(token);
    }

    @Test
    void anUnknownTokenIsNotFoundAndTheErrorNeverRepeatsIt() throws Exception {
        MvcResult result = charge(merchant, "tok_does_not_exist", INTERNAL_TOKEN);

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        // the error shows only the start of the token it could not find
        assertThat(result.getResponse().getContentAsString()).contains("CARDTOKEN_NOT_FOUND").contains("tok_****").doesNotContain("tok_does_not_exist");
    }

    @Test
    void theChargeEndpointIsRefusedWithoutTheServiceToken() throws Exception {
        String token = token(tokenize(merchant, VISA));

        assertThat(charge(merchant, token, null).getResponse().getStatus()).isEqualTo(401);
        assertThat(charge(merchant, token, "a-wrong-token").getResponse().getStatus()).isEqualTo(401);
    }
}
