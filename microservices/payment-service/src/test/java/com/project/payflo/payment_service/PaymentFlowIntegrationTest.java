package com.project.payflo.payment_service;

import com.jayway.jsonpath.JsonPath;
import com.project.payflo.common_lib.dto.PaymentProcessorResponse;
import com.project.payflo.payment_service.client.VaultServiceClient;
import com.project.payflo.payment_service.simulator.SimulatorConfig;
import com.project.payflo.payment_service.velocity.CardVelocityProperties;
import com.project.payflo.test_support.PayfloIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.project.payflo.test_support.SharedInfrastructure.bootstrapServers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The payment service end to end through its HTTP API, on real PostgreSQL, Redis and Kafka. Only the vault (another
 * service) is a stub. The simulated bank approves everything quickly, so a payment's whole life takes seconds.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "payment.simulator.chaos-mode=SUCCESS",
        "payment.simulator.poll-interval-ms=500",
        "payment.refund.success-rate=100",
        "payment.refund.delay-seconds=1",
        "payment.refund.poll-interval-ms=500"
})
class PaymentFlowIntegrationTest extends PayfloIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private SimulatorConfig simulatorConfig;

    @Autowired
    private CardVelocityProperties cardVelocity;

    @MockitoBean
    private VaultServiceClient vault;

    private final UUID merchant = UUID.randomUUID();

    // ---- helpers

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID merchantId) {
        return request.header("X-Merchant-Id", merchantId.toString()).contentType(MediaType.APPLICATION_JSON);
    }

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private static String json(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static String field(MvcResult result, String path) throws Exception {
        return JsonPath.read(json(result), path).toString();
    }

    private String createOrder(UUID merchantId, int amountUnits) throws Exception {
        MvcResult order = call(as(post("/v1/orders"), merchantId)
                .content("{\"amount\":{\"amountUnits\":" + amountUnits + ",\"currency\":\"INR\"}}"));
        assertThat(order.getResponse().getStatus()).as(json(order)).isEqualTo(201);
        return field(order, "$.id");
    }

    private MvcResult pay(UUID merchantId, String orderId, String method, String details) throws Exception {
        return call(as(post("/v1/payments"), merchantId)
                .content("{\"orderId\":\"" + orderId + "\",\"method\":\"" + method + "\",\"methodDetails\":" + details + "}"));
    }

    private String paymentStatus(UUID merchantId, String paymentId) throws Exception {
        return field(call(as(get("/v1/payments/" + paymentId), merchantId)), "$.status");
    }

    private void awaitStatus(UUID merchantId, String paymentId, String expected) {
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(paymentStatus(merchantId, paymentId)).isEqualTo(expected));
    }

    private String upiPayment(UUID merchantId, int amountUnits) throws Exception {
        String orderId = createOrder(merchantId, amountUnits);
        MvcResult payment = pay(merchantId, orderId, "UPI", "{\"vpa\":\"asha@okaxis\"}");
        assertThat(payment.getResponse().getStatus()).as(json(payment)).isEqualTo(201);
        return field(payment, "$.id");
    }

    // ---- payments

    @Test
    void aUpiPaymentIsAuthorizedCapturedAndTheOrderPaid() throws Exception {
        String orderId = createOrder(merchant, 50_000);

        MvcResult payment = pay(merchant, orderId, "UPI", "{\"vpa\":\"asha@okaxis\"}");
        assertThat(field(payment, "$.status")).isEqualTo("AUTHORIZING");
        assertThat(field(payment, "$.method")).isEqualTo("UPI");
        String paymentId = field(payment, "$.id");

        awaitStatus(merchant, paymentId, "CAPTURED");
        assertThat(field(call(as(get("/v1/orders/" + orderId), merchant)), "$.status")).isEqualTo("PAID");

        // every step went through the state machine, which logged it
        Integer transitions = jdbc.queryForObject("select count(*) from payment_transition_log where payment_id = ?::uuid", Integer.class, paymentId);
        assertThat(transitions).isGreaterThanOrEqualTo(3);
        // and the method is stored by name (V2 migration), not by ordinal
        assertThat(jdbc.queryForObject("select method from payment where id = ?::uuid", String.class, paymentId)).isEqualTo("UPI");
    }

    @Test
    void aCardPaymentChargesTheVaultWithThePaymentsMerchantAndToken() throws Exception {
        when(vault.charge(any())).thenReturn(new PaymentProcessorResponse.Pending("VAULT-REF-1"));
        String orderId = createOrder(merchant, 12_000);

        MvcResult payment = pay(merchant, orderId, "CARD", "{\"token\":\"tok_abc123\"}");
        String paymentId = field(payment, "$.id");

        awaitStatus(merchant, paymentId, "CAPTURED");
        verify(vault).charge(org.mockito.ArgumentMatchers.argThat(request ->
                request.merchantId().equals(merchant) && "tok_abc123".equals(request.token())
                        && request.amount().getAmountUnits() == 12_000));
    }

    @Test
    void aCardTheVaultDeclinesFailsThePaymentAndTheOrderCanBePaidAgain() throws Exception {
        when(vault.charge(any())).thenReturn(new PaymentProcessorResponse.Failure("CARD_DECLINED", "The card was declined"));
        String orderId = createOrder(merchant, 9_000);

        MvcResult declined = pay(merchant, orderId, "CARD", "{\"token\":\"tok_declined\"}");

        assertThat(declined.getResponse().getStatus()).isEqualTo(201); // a decline is an outcome, not an HTTP error
        assertThat(field(declined, "$.status")).isEqualTo("FAILED");
        assertThat(field(declined, "$.errorCode")).isEqualTo("CARD_DECLINED");

        MvcResult retry = pay(merchant, orderId, "UPI", "{\"vpa\":\"asha@okaxis\"}");
        assertThat(retry.getResponse().getStatus()).as(json(retry)).isEqualTo(201);
        awaitStatus(merchant, field(retry, "$.id"), "CAPTURED");
    }

    @Test
    void aVaultThatCannotBeReachedCompensatesThePaymentAndNothingIsCharged() throws Exception {
        when(vault.charge(any())).thenThrow(new RuntimeException("vault-service is down", new ConnectException("Connection refused")));
        String orderId = createOrder(merchant, 7_000);

        MvcResult payment = pay(merchant, orderId, "CARD", "{\"token\":\"tok_any\"}");

        assertThat(field(payment, "$.status")).isEqualTo("FAILED");
        assertThat(field(payment, "$.errorCode")).isEqualTo("PAYMENT_GATEWAY_ROUTER_UNREACHABLE");
        assertThat(json(payment)).doesNotContain("Connection refused"); // never the underlying exception text
        // the saga's compensation is announced through the outbox, in the same transaction as the failure
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_type = 'PAYMENT_AUTHORIZATION_COMPENSATED' and payload::text like ?",
                Integer.class, "%" + field(payment, "$.id") + "%")).isEqualTo(1);
    }

    @Test
    void aVaultCallThatTimedOutAfterItWasSentIsNotFailedBecauseTheChargeMayHaveHappened() throws Exception {
        when(vault.charge(any())).thenThrow(new RuntimeException("slow", new SocketTimeoutException("Read timed out")));
        String orderId = createOrder(merchant, 8_000);

        MvcResult payment = pay(merchant, orderId, "CARD", "{\"token\":\"tok_slow\"}");

        // left for the bank's answer to settle, not failed (and never retried: that could charge twice)
        assertThat(field(payment, "$.status")).isEqualTo("AUTHORIZING");
        awaitStatus(merchant, field(payment, "$.id"), "CAPTURED");
    }

    @Test
    void anOrderTakesOnePaymentAtATime() throws Exception {
        String orderId = createOrder(merchant, 4_000);
        pay(merchant, orderId, "UPI", "{\"vpa\":\"asha@okaxis\"}");

        MvcResult second = pay(merchant, orderId, "UPI", "{\"vpa\":\"other@okaxis\"}");

        assertThat(second.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(second, "$.errorCode")).isIn("ORDER_PAYMENT_IN_PROGRESS", "ORDER_NOT_PAYABLE");
    }

    // ---- card testing

    private MvcResult payByCard(UUID merchantId, String orderId) throws Exception {
        return pay(merchantId, orderId, "CARD", "{\"token\":\"tok_stolen_list\"}");
    }

    @Test
    void aMerchantWhoseCardPaymentsMostlyFailIsRefusedForCardsOnlyAndOnlyThatMerchant() throws Exception {
        cardVelocity.setMinFailures(4);
        try {
            UUID bot = UUID.randomUUID();
            when(vault.charge(any())).thenReturn(new PaymentProcessorResponse.Failure("CARD_DECLINED", "The card was declined"));
            for (int i = 1; i <= 4; i++) {
                MvcResult declined = payByCard(bot, createOrder(bot, 100 + i));
                assertThat(declined.getResponse().getStatus()).as("card " + i).isEqualTo(201);
                assertThat(field(declined, "$.status")).isEqualTo("FAILED");
            }
            String nextOrder = createOrder(bot, 500);

            MvcResult refused = payByCard(bot, nextOrder);

            assertThat(refused.getResponse().getStatus()).isEqualTo(429);
            assertThat(field(refused, "$.errorCode")).isEqualTo("CARD_TESTING_SUSPECTED");
            assertThat(Integer.parseInt(refused.getResponse().getHeader("Retry-After"))).isBetween(1, 600);
            // refused before anything was written, and before the vault was asked to charge a fifth time
            assertThat(jdbc.queryForObject("select count(*) from payment where order_id = ?::uuid", Integer.class, nextOrder)).isZero();
            verify(vault, org.mockito.Mockito.times(4)).charge(any());

            // the same merchant can still take UPI, and the refused order is still payable
            MvcResult upi = pay(bot, nextOrder, "UPI", "{\"vpa\":\"asha@okaxis\"}");
            assertThat(upi.getResponse().getStatus()).as(json(upi)).isEqualTo(201);

            // another merchant, on the same vault answer, is not touched
            UUID honest = UUID.randomUUID();
            when(vault.charge(any())).thenReturn(new PaymentProcessorResponse.Pending("CARD_PROCESSOR_ok"));
            assertThat(payByCard(honest, createOrder(honest, 700)).getResponse().getStatus()).isEqualTo(201);
        } finally {
            cardVelocity.setMinFailures(20);
        }
    }

    @Test
    void aMerchantWhoseDeclinesAreAFewInTenIsNeverRefused() throws Exception {
        cardVelocity.setMinFailures(3);
        try {
            UUID busy = UUID.randomUUID();
            // 3 declines among 12 card payments is 25%: more than the minimum count, far below the share that means testing
            for (int i = 1; i <= 12; i++) {
                boolean decline = i % 4 == 0;
                when(vault.charge(any())).thenReturn(decline
                        ? new PaymentProcessorResponse.Failure("CARD_DECLINED", "The card was declined")
                        : new PaymentProcessorResponse.Pending("CARD_PROCESSOR_ok"));
                MvcResult result = payByCard(busy, createOrder(busy, 1000 + i));
                assertThat(result.getResponse().getStatus()).as("card " + i + ": " + json(result)).isEqualTo(201);
            }
        } finally {
            cardVelocity.setMinFailures(20);
        }
    }

    @Test
    void anOrderTakesFiveCardPaymentsAndNotASixthButStillTakesUpi() throws Exception {
        when(vault.charge(any())).thenReturn(new PaymentProcessorResponse.Failure("CARD_DECLINED", "The card was declined"));
        String orderId = createOrder(merchant, 2_500);
        for (int i = 1; i <= 5; i++) {
            assertThat(field(payByCard(merchant, orderId), "$.status")).as("card " + i).isEqualTo("FAILED");
        }

        MvcResult sixth = payByCard(merchant, orderId);

        assertThat(sixth.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(sixth, "$.errorCode")).isEqualTo("ORDER_CARD_ATTEMPTS_EXCEEDED");
        assertThat(jdbc.queryForObject("select count(*) from payment where order_id = ?::uuid and method = 'CARD'", Integer.class, orderId)).isEqualTo(5);
        assertThat(pay(merchant, orderId, "UPI", "{\"vpa\":\"asha@okaxis\"}").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void aFailureOfOurOwnVaultIsNotCountedAgainstTheCard() throws Exception {
        cardVelocity.setMinFailures(2);
        try {
            UUID unlucky = UUID.randomUUID();
            when(vault.charge(any())).thenReturn(new PaymentProcessorResponse.Failure("VAULT_CHARGE_FAILED", "The card charge could not be completed"));
            for (int i = 1; i <= 4; i++) {
                assertThat(payByCard(unlucky, createOrder(unlucky, 300 + i)).getResponse().getStatus()).isEqualTo(201);
            }

            // four failed payments, none the card's fault: the fifth is still allowed
            when(vault.charge(any())).thenReturn(new PaymentProcessorResponse.Pending("CARD_PROCESSOR_ok"));
            assertThat(payByCard(unlucky, createOrder(unlucky, 399)).getResponse().getStatus()).isEqualTo(201);
        } finally {
            cardVelocity.setMinFailures(20);
        }
    }

    // ---- a capture the bank refuses

    private String captureFailPayment(UUID merchantId) throws Exception {
        String orderId = createOrder(merchantId, 18_000);
        MvcResult payment = pay(merchantId, orderId, "UPI", "{\"vpa\":\"capturefail@okaxis\"}");
        assertThat(payment.getResponse().getStatus()).as(json(payment)).isEqualTo(201);
        return field(payment, "$.id");
    }

    @Test
    void aCaptureTheBankRefusesLeavesThePaymentAuthorizedAndTheMerchantsRetryCapturesIt() throws Exception {
        String paymentId = captureFailPayment(merchant);

        // approved, then the automatic capture refused: held, not failed, and the order is still unpaid
        awaitStatus(merchant, paymentId, "AUTHORIZED");
        MvcResult held = call(as(get("/v1/payments/" + paymentId), merchant));
        assertThat(field(held, "$.errorCode")).isEqualTo("CAPTURE_DECLINED");
        String orderId = field(held, "$.orderId");
        assertThat(field(call(as(get("/v1/orders/" + orderId), merchant)), "$.status")).isEqualTo("ATTEMPTED");
        assertThat(jdbc.queryForObject("select count(*) from payment_transition_log where payment_id = ?::uuid and event = 'CAPTURE_FAIL'",
                Integer.class, paymentId)).isEqualTo(1);
        // and the webhook for it says why
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ?::uuid and event_type = 'PAYMENT_STATUS_CHANGED' "
                + "and payload::text like '%CAPTURE_DECLINED%'", Integer.class, paymentId)).isEqualTo(1);
        // the order is held by the authorization, so a second payment can't be started meanwhile
        assertThat(pay(merchant, orderId, "UPI", "{\"vpa\":\"asha@okaxis\"}").getResponse().getStatus()).isEqualTo(400);

        // the retry goes through, and the error of the attempt that failed is gone
        MvcResult retry = call(as(post("/v1/payments/" + paymentId + "/capture"), merchant));
        assertThat(retry.getResponse().getStatus()).as(json(retry)).isEqualTo(200);
        assertThat(field(retry, "$.status")).isEqualTo("CAPTURED");
        assertThat(json(retry)).doesNotContain("errorCode");
        assertThat(field(call(as(get("/v1/orders/" + orderId), merchant)), "$.status")).isEqualTo("PAID");
    }

    @Test
    void aCaptureThatKeepsBeingRefusedStaysAuthorizedAndReportsEachRefusal() throws Exception {
        String paymentId = captureFailPayment(merchant);
        awaitStatus(merchant, paymentId, "AUTHORIZED");
        // the simulator only lets a capture through after this many refusals; the automatic one was the first
        simulatorConfig.getCapture().setFailuresBeforeSuccess(3);
        try {
            MvcResult second = call(as(post("/v1/payments/" + paymentId + "/capture"), merchant));
            assertThat(second.getResponse().getStatus()).isEqualTo(200);
            assertThat(field(second, "$.status")).isEqualTo("AUTHORIZED");
            assertThat(field(second, "$.errorCode")).isEqualTo("CAPTURE_DECLINED");

            MvcResult third = call(as(post("/v1/payments/" + paymentId + "/capture"), merchant));
            assertThat(field(third, "$.status")).isEqualTo("AUTHORIZED");

            MvcResult fourth = call(as(post("/v1/payments/" + paymentId + "/capture"), merchant));
            assertThat(field(fourth, "$.status")).isEqualTo("CAPTURED");
        } finally {
            simulatorConfig.getCapture().setFailuresBeforeSuccess(1);
        }
    }

    @Test
    void aCardWhoseCaptureTheBankRefusesBehavesTheSameWay() throws Exception {
        when(vault.charge(any())).thenReturn(new PaymentProcessorResponse.Pending("CARD_PROCESSOR_CAPTURE_FAIL_x1"));
        String orderId = createOrder(merchant, 11_000);

        String paymentId = field(pay(merchant, orderId, "CARD", "{\"token\":\"tok_capture_fail\"}"), "$.id");

        awaitStatus(merchant, paymentId, "AUTHORIZED");
        assertThat(field(call(as(post("/v1/payments/" + paymentId + "/capture"), merchant)), "$.status")).isEqualTo("CAPTURED");
    }

    @Test
    void aPaymentThatIsNotAuthorizedCannotBeCaptured() throws Exception {
        String paymentId = upiPayment(merchant, 3_000);
        awaitStatus(merchant, paymentId, "CAPTURED");

        MvcResult again = call(as(post("/v1/payments/" + paymentId + "/capture"), merchant));

        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(field(again, "$.errorCode")).isEqualTo("INVALID_STATE_TRANSITION");
    }

    // ---- refunds

    @Test
    void refundsAreProcessedAndTheyMoveThePaymentThroughPartialToFullyRefunded() throws Exception {
        String paymentId = upiPayment(merchant, 10_000);
        awaitStatus(merchant, paymentId, "CAPTURED");

        MvcResult first = call(as(post("/v1/payments/" + paymentId + "/refunds"), merchant).content("{\"amountUnits\":4000}"));
        assertThat(first.getResponse().getStatus()).as(json(first)).isEqualTo(201);
        assertThat(field(first, "$.status")).isEqualTo("PENDING");
        awaitStatus(merchant, paymentId, "PARTIALLY_REFUNDED");

        MvcResult tooMuch = call(as(post("/v1/payments/" + paymentId + "/refunds"), merchant).content("{\"amountUnits\":7000}"));
        assertThat(tooMuch.getResponse().getStatus()).isEqualTo(400);

        MvcResult rest = call(as(post("/v1/payments/" + paymentId + "/refunds"), merchant).content("{}"));
        assertThat(field(rest, "$.amount.amountUnits")).isEqualTo("6000"); // what was left
        awaitStatus(merchant, paymentId, "REFUNDED");
        assertThat(jdbc.queryForObject("select sum(amount_units) from refund where payment_id = ?::uuid and status = 'PROCESSED'", Long.class, paymentId))
                .isEqualTo(10_000L);
    }

    // ---- idempotency, on real Redis

    @Test
    void aRetryOfAnOrderCreationReturnsTheSameOrderAndAChangedRetryIsRefused() throws Exception {
        String key = "idem-" + UUID.randomUUID();
        String body = "{\"amount\":{\"amountUnits\":3000,\"currency\":\"INR\"}}";

        MvcResult first = call(as(post("/v1/orders"), merchant).header("X-Idempotency-Key", key).content(body));
        MvcResult retry = call(as(post("/v1/orders"), merchant).header("X-Idempotency-Key", key).content(body));
        MvcResult changed = call(as(post("/v1/orders"), merchant).header("X-Idempotency-Key", key)
                .content("{\"amount\":{\"amountUnits\":9999,\"currency\":\"INR\"}}"));

        assertThat(field(retry, "$.id")).isEqualTo(field(first, "$.id"));
        assertThat(changed.getResponse().getStatus()).isEqualTo(422);
        assertThat(field(changed, "$.errorCode")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(jdbc.queryForObject("select count(*) from order_record where merchant_id = ?::uuid", Integer.class, merchant.toString())).isEqualTo(1);
    }

    @Test
    void thePaymentKeyAlsoHoldsInTheDatabaseWhenRedisHasForgottenIt() throws Exception {
        String orderId = createOrder(merchant, 5_000);
        String otherOrder = createOrder(merchant, 6_000);
        String key = "pay-" + UUID.randomUUID();
        String details = "{\"vpa\":\"asha@okaxis\"}";

        MvcResult first = call(as(post("/v1/payments"), merchant).header("X-Idempotency-Key", key)
                .content("{\"orderId\":\"" + orderId + "\",\"method\":\"UPI\",\"methodDetails\":" + details + "}"));
        // forget it in Redis, as an eviction or an expiry would
        redis.delete(redis.keys("idempotency:" + merchant + ":*"));
        MvcResult forOtherOrder = call(as(post("/v1/payments"), merchant).header("X-Idempotency-Key", key)
                .content("{\"orderId\":\"" + otherOrder + "\",\"method\":\"UPI\",\"methodDetails\":" + details + "}"));

        assertThat(field(first, "$.status")).isNotBlank();
        assertThat(forOtherOrder.getResponse().getStatus()).isEqualTo(422);
        assertThat(jdbc.queryForObject("select count(*) from payment where merchant_id = ?::uuid", Integer.class, merchant.toString())).isEqualTo(1);
    }

    @Test
    void theOrderKeyAlsoHoldsInTheDatabaseWhenRedisHasForgottenIt() throws Exception {
        String key = "ord-db-" + UUID.randomUUID();
        String body = "{\"amount\":{\"amountUnits\":4200,\"currency\":\"INR\"},\"notes\":{\"ref\":\"x\"}}";
        MvcResult first = call(as(post("/v1/orders"), merchant).header("X-Idempotency-Key", key).content(body));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        // forget it in Redis, as an outage or an eviction would, and retry: the database answers
        redis.delete(redis.keys("idempotency:" + merchant + ":*"));
        MvcResult retry = call(as(post("/v1/orders"), merchant).header("X-Idempotency-Key", key).content(body));
        MvcResult changed = call(as(post("/v1/orders"), merchant).header("X-Idempotency-Key", key)
                .content("{\"amount\":{\"amountUnits\":9900,\"currency\":\"INR\"},\"notes\":{\"ref\":\"x\"}}"));

        assertThat(field(retry, "$.id")).as("the same order, not a second").isEqualTo(field(first, "$.id"));
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(changed.getResponse().getStatus()).isEqualTo(422);
        assertThat(field(changed, "$.errorCode")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(jdbc.queryForObject("select count(*) from order_record where merchant_id = ?::uuid and idempotency_key = ?", Integer.class,
                merchant.toString(), key)).isEqualTo(1);
    }

    @Test
    void manyRequestsWithOneKeyAtTheSameInstantCreateExactlyOneOrder() throws Exception {
        String key = "ord-race-" + UUID.randomUUID();
        String body = "{\"amount\":{\"amountUnits\":1300,\"currency\":\"INR\"}}";
        var pool = java.util.concurrent.Executors.newFixedThreadPool(10);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<MvcResult>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    // Redis forgets the claim, so the request reaches the database each time: the worst case for the race
                    redis.delete(redis.keys("idempotency:" + merchant + ":*"));
                    return call(as(post("/v1/orders"), merchant).header("X-Idempotency-Key", key).content(body));
                }));
            }
            start.countDown();
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (var call : calls) {
                MvcResult result = call.get();
                assertThat(result.getResponse().getStatus()).as(json(result)).isIn(200, 201, 409);
                if (result.getResponse().getStatus() != 409) {
                    ids.add(field(result, "$.id"));
                }
            }
            assertThat(ids).as("every request that was answered got the same order").hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from order_record where merchant_id = ?::uuid and idempotency_key = ?", Integer.class,
                merchant.toString(), key)).isEqualTo(1);
    }

    @Test
    void anIdempotencyKeyLongerThanTheColumnIsABadRequestNotAServerError() throws Exception {
        MvcResult result = call(as(post("/v1/orders"), merchant).header("X-Idempotency-Key", "k".repeat(150))
                .content("{\"amount\":{\"amountUnits\":100,\"currency\":\"INR\"}}"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(result, "$.errorCode")).isEqualTo("IDEMPOTENCY_KEY_TOO_LONG");
    }

    // ---- isolation

    @Test
    void anotherMerchantCannotSeeOrPayOrRefundThisMerchantsThings() throws Exception {
        UUID stranger = UUID.randomUUID();
        String orderId = createOrder(merchant, 2_000);
        String paymentId = upiPayment(merchant, 2_500);

        assertThat(call(as(get("/v1/orders/" + orderId), stranger)).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(as(get("/v1/payments/" + paymentId), stranger)).getResponse().getStatus()).isEqualTo(404);
        assertThat(pay(stranger, orderId, "UPI", "{\"vpa\":\"x@okaxis\"}").getResponse().getStatus()).isEqualTo(404);
        assertThat(call(as(post("/v1/payments/" + paymentId + "/refunds"), stranger).content("{}")).getResponse().getStatus()).isEqualTo(404);
    }

    // ---- events

    @Test
    void theOutboxPublishesOrderAndPaymentEventsToKafka() throws Exception {
        String orderId = createOrder(merchant, 1_500);
        MvcResult payment = pay(merchant, orderId, "UPI", "{\"vpa\":\"asha@okaxis\"}");
        String paymentId = field(payment, "$.id");
        awaitStatus(merchant, paymentId, "CAPTURED");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from outbox_event where status = 'PENDING' and aggregate_id in (?::uuid, ?::uuid)", Integer.class, orderId, paymentId)).isZero());

        assertThat(kafkaHas("orders.events", orderId)).as("ORDER_CREATED reached orders.events").isTrue();
        assertThat(kafkaHas("payments.events", paymentId)).as("a payment event reached payments.events").isTrue();
    }

    private static boolean kafkaHas(String topic, String text) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value().contains(text) || (record.key() != null && record.key().contains(text))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // ---- analytics, on real SQL

    @Test
    void theDashboardAddsUpWhatWasCapturedAndRefundedToday() throws Exception {
        UUID analyst = UUID.randomUUID();
        String a = upiPayment(analyst, 10_000);
        String b = upiPayment(analyst, 25_000);
        awaitStatus(analyst, a, "CAPTURED");
        awaitStatus(analyst, b, "CAPTURED");
        call(as(post("/v1/payments/" + a + "/refunds"), analyst).content("{\"amountUnits\":3000}"));
        // the dashboard counts refunds the bank has processed, which comes after the payment becomes PARTIALLY_REFUNDED
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from refund where payment_id = ?::uuid and status = 'PROCESSED'", Integer.class, a)).isEqualTo(1));

        MvcResult dashboard = call(as(get("/v1/analytics/dashboard"), analyst));

        assertThat(dashboard.getResponse().getStatus()).as(json(dashboard)).isEqualTo(200);
        assertThat(dashboard.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(field(dashboard, "$.todaySummary.grossAmountUnits")).isEqualTo("35000");
        assertThat(field(dashboard, "$.todaySummary.capturedCount")).isEqualTo("2");
        assertThat(field(dashboard, "$.todaySummary.refundedAmountUnits")).isEqualTo("3000");
        assertThat(field(dashboard, "$.todaySummary.netAmountUnits")).isEqualTo("32000");
        assertThat(field(dashboard, "$.todaySummary.successRate")).isEqualTo("1.0");
        assertThat(field(dashboard, "$.byMethod[0].method")).isEqualTo("UPI");
        assertThat((List<?>) JsonPath.read(json(dashboard), "$.daily")).hasSize(7);

        MvcResult report = call(as(get("/v1/analytics/report?granularity=MONTH"), analyst));
        assertThat(field(report, "$.totals.grossAmountUnits")).isEqualTo("35000");

        // another merchant sees none of it
        MvcResult other = call(as(get("/v1/analytics/dashboard"), UUID.randomUUID()));
        assertThat(field(other, "$.last7Days.grossAmountUnits")).isEqualTo("0");
    }
}
