package com.project.payflo.operations_service;

import com.project.payflo.common_lib.dto.WebhookTarget;
import com.project.payflo.common_lib.util.WebhookSignatures;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Webhook delivery from the Kafka event to the merchant's server: a real event on a real topic, a real HTTP receiver, a
 * real retry queue in Redis and a real database. Only merchant-service (which knows the merchant's webhook URL and
 * secret) is a stub.
 */
class WebhookDeliveryIntegrationTest extends OperationsIntegrationTest {

    private record Received(String body, String signature, String timestamp, String eventId) {}

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    private final UUID merchant = UUID.randomUUID();
    private final UUID configId = UUID.randomUUID();
    private final String secret = "whsec_" + UUID.randomUUID();
    private String url;

    private HttpServer receiver;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger respondWith = new AtomicInteger(200);

    @BeforeEach
    void aMerchantServerAndAWebhookConfiguredForIt() throws IOException {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(body, exchange.getRequestHeaders().getFirst("X-PayFlo-Signature"),
                    exchange.getRequestHeaders().getFirst("X-PayFlo-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-PayFlo-Event-Id")));
            exchange.sendResponseHeaders(respondWith.get(), -1);
            exchange.close();
        });
        receiver.start();
        url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
        WebhookTarget target = new WebhookTarget(configId, url, secret);
        when(merchantClient.getActiveConfigsForEvent(eq(merchant), any())).thenReturn(List.of(target));
        // merchant-service is asked for the secret again at every attempt, so a rotation reaches a retry
        when(merchantClient.getWebhookTarget(merchant, configId)).thenReturn(target);
    }

    // What a merchant's own server would do with the request, using the same published rule.
    private static boolean verifies(Received call, String secret) {
        return WebhookSignatures.verify(secret, call.signature(), call.timestamp(), call.body(),
                WebhookSignatures.DEFAULT_TOLERANCE_SECONDS, Instant.now());
    }

    @AfterEach
    void stopTheServer() {
        receiver.stop(0);
    }

    // ---- helpers

    private String publishPaymentEvent() {
        String eventId = UUID.randomUUID().toString();
        // occurredAt is what payment-service stamps when the change is committed
        kafka.send("payments.events", eventId, Map.of("eventId", eventId, "eventType", "PAYMENT_STATUS_CHANGED",
                "occurredAt", System.currentTimeMillis(),
                "data", Map.of("merchantId", merchant.toString(), "paymentId", UUID.randomUUID().toString(), "status", "CAPTURED")));
        return eventId;
    }

    private Map<String, Object> deliveryOf(String eventId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from webhook_event where event_id = ?", eventId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private void awaitDelivery(String eventId, String status) {
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(String.valueOf(deliveryOf(eventId).get("status"))).isEqualTo(status));
    }

    // The first back-off is a minute. The retry sits in Redis with that time as its score and in the database as
    // next_retry_at; both must move to bring a retry forward (the claim checks the database, the poller the queue).
    private void bringRetryForward(String eventId) {
        Map<String, Object> event = deliveryOf(eventId);
        jdbc.update("update webhook_event set next_retry_at = now() - interval '1 second' where event_id = ?", eventId);
        redis.opsForZSet().add("webhook-retry", event.get("id").toString(), 0);
    }

    // ---- delivery

    @Test
    void aSignedWebhookReachesTheMerchantsServerAndIsRecordedDelivered() {
        String eventId = publishPaymentEvent();

        awaitDelivery(eventId, "DELIVERED");

        assertThat(received).hasSize(1);
        Received call = received.getFirst();
        assertThat(call.eventId()).isEqualTo(eventId);
        assertThat(call.body()).contains("\"event\":\"PAYMENT_STATUS_CHANGED\"").contains(eventId).contains("CAPTURED");
        // the receiver can check it came from PayFlo, and that it is fresh, with the secret only the two of them share
        assertThat(verifies(call, secret)).isTrue();
        assertThat(call.timestamp()).matches("\\d{10}");
        assertThat(deliveryOf(eventId).get("attempts")).isEqualTo(1);
        // the secret itself is never kept with the event, only which config it was for
        assertThat(deliveryOf(eventId).get("config_id")).isEqualTo(configId);
        assertThat(deliveryOf(eventId).get("signature")).isNull();
        assertThat(deliveryOf(eventId).values().stream().filter(v -> v != null && v.toString().contains(secret))).isEmpty();
    }

    @Test
    void theTimeFromTheChangeToTheDeliveryIsRecordedForTheSla() {
        io.micrometer.core.instrument.Timer timer = meters.find("payflo.webhook.delivery.latency").tag("retried", "false").timer();
        long before = timer == null ? 0 : timer.count();

        String eventId = publishPaymentEvent();
        awaitDelivery(eventId, "DELIVERED");

        // the change's own time travelled with the event and is kept on the delivery ...
        assertThat(deliveryOf(eventId).get("event_occurred_at")).isNotNull();
        // ... and delivering it adds an observation to the histogram the 30-second SLA is read from
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(meters.get("payflo.webhook.delivery.latency").tag("retried", "false").timer().count())
                        .isGreaterThan(before));
        // this delivery's own time from the change to the answer, which is what that observation measured
        Duration latency = jdbc.queryForObject(
                "select extract(epoch from (delivered_at - event_occurred_at)) from webhook_event where event_id = ?",
                (rs, row) -> Duration.ofMillis((long) (rs.getDouble(1) * 1000)), eventId);
        assertThat(latency).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void aBurstOfEventsIsDeliveredFarFasterThanTheOldHundredASecond() {
        int events = 3_000;
        for (int i = 0; i < events; i++) {
            publishPaymentEvent();
        }

        // One poll of 100 a second would need 30 s for these (and the consumer, one commit per event, about as long).
        await().atMost(Duration.ofSeconds(25)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(received.size()).isGreaterThanOrEqualTo(events));
        assertThat(jdbc.queryForObject("select count(*) from webhook_event where merchant_id = ? and status <> 'DELIVERED'",
                Long.class, merchant)).isZero();
    }

    // The URL is checked before every delivery; following a redirect would go to wherever the merchant's server pointed,
    // past that check (an internal address, say). A 3xx is a failed attempt like any other answer that isn't a 2xx.
    @Test
    void aRedirectIsNotFollowedAndCountsAsAFailedAttempt() throws Exception {
        AtomicInteger hitsElsewhere = new AtomicInteger();
        HttpServer elsewhere = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        elsewhere.createContext("/hit", exchange -> {
            hitsElsewhere.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        elsewhere.start();
        try {
            receiver.createContext("/moved", exchange -> {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + elsewhere.getAddress().getPort() + "/hit");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            WebhookTarget moved = new WebhookTarget(configId, "http://127.0.0.1:" + receiver.getAddress().getPort() + "/moved", secret);
            when(merchantClient.getActiveConfigsForEvent(eq(merchant), any())).thenReturn(List.of(moved));
            when(merchantClient.getWebhookTarget(merchant, configId)).thenReturn(moved);

            String eventId = publishPaymentEvent();

            awaitDelivery(eventId, "FAILED");
            assertThat(deliveryOf(eventId).get("last_response_code")).isEqualTo(302);
            assertThat(hitsElsewhere.get()).as("the redirect target must never be called").isZero();
        } finally {
            elsewhere.stop(0);
        }
    }

    @Test
    void aSignatureIsNotValidWithAnotherSecretOrAfterTheWindowOrOnAChangedBody() {
        String eventId = publishPaymentEvent();
        awaitDelivery(eventId, "DELIVERED");
        Received call = received.getFirst();

        assertThat(verifies(call, "someone-elses-secret")).isFalse();
        assertThat(WebhookSignatures.verify(secret, call.signature(), call.timestamp(), call.body() + " ", 300, Instant.now())).isFalse();
        // the same request replayed an hour from now is outside the receiver's window
        assertThat(WebhookSignatures.verify(secret, call.signature(), call.timestamp(), call.body(), 300,
                Instant.now().plusSeconds(3600))).isFalse();
        // and a replay that swaps in a fresh timestamp no longer matches the signature
        assertThat(WebhookSignatures.verify(secret, call.signature(), String.valueOf(Instant.now().getEpochSecond() + 5),
                call.body(), 300, Instant.now())).isFalse();
    }

    @Test
    void aMerchantServerThatFailsIsRetriedWithTheSameBodyAndAFreshlySignedTimestamp() throws Exception {
        respondWith.set(500);
        String eventId = publishPaymentEvent();

        awaitDelivery(eventId, "FAILED");
        assertThat(deliveryOf(eventId).get("attempts")).isEqualTo(1);
        assertThat(deliveryOf(eventId).get("next_retry_at")).as("a retry is scheduled").isNotNull();

        // the merchant fixes its server; the first back-off is a minute, so bring the retry forward
        respondWith.set(200);
        Thread.sleep(1_100); // so the second attempt's timestamp can differ from the first's
        bringRetryForward(eventId);

        awaitDelivery(eventId, "DELIVERED");
        assertThat(deliveryOf(eventId).get("attempts")).isEqualTo(2);
        assertThat(received).hasSize(2);
        assertThat(received.get(1).body()).isEqualTo(received.get(0).body());
        // a retry is a new attempt: its own timestamp and the signature over it, both valid for the receiver
        assertThat(Long.parseLong(received.get(1).timestamp())).isGreaterThan(Long.parseLong(received.get(0).timestamp()));
        assertThat(received.get(1).signature()).isNotEqualTo(received.get(0).signature());
        assertThat(verifies(received.get(0), secret)).isTrue();
        assertThat(verifies(received.get(1), secret)).isTrue();
        assertThat(received.get(1).eventId()).isEqualTo(received.get(0).eventId()); // lets the receiver drop a duplicate
    }

    @Test
    void aSecretRotatedBetweenAttemptsSignsTheRetryWithTheNewOne() {
        respondWith.set(500);
        String eventId = publishPaymentEvent();
        awaitDelivery(eventId, "FAILED");

        String rotated = "whsec_rotated_" + UUID.randomUUID();
        when(merchantClient.getWebhookTarget(merchant, configId)).thenReturn(new WebhookTarget(configId, url, rotated));
        respondWith.set(200);
        bringRetryForward(eventId);

        awaitDelivery(eventId, "DELIVERED");
        assertThat(verifies(received.get(0), secret)).isTrue();
        assertThat(verifies(received.get(1), rotated)).isTrue();
        assertThat(verifies(received.get(1), secret)).isFalse();
    }

    @Test
    void aConfigTheMerchantDeletedMeansTheEventIsDeadLetteredAtOnceWithoutBeingSent() {
        when(merchantClient.getWebhookTarget(merchant, configId)).thenThrow(notFound());
        String eventId = publishPaymentEvent();

        awaitDelivery(eventId, "DEAD");

        assertThat(received).isEmpty();
        assertThat(String.valueOf(deliveryOf(eventId).get("last_response_body"))).contains("WEBHOOK_CONFIG_DELETED");
        assertThat(jdbc.queryForObject("select count(*) from dlq_event where webhook_event_id = ?::uuid", Integer.class,
                deliveryOf(eventId).get("id").toString())).isEqualTo(1);
    }

    @Test
    void merchantServiceBeingDownWhenSigningCountsAsAFailedAttemptAndIsRetried() {
        when(merchantClient.getWebhookTarget(merchant, configId)).thenThrow(new RuntimeException("merchant-service is down"));
        String eventId = publishPaymentEvent();

        awaitDelivery(eventId, "FAILED");
        assertThat(String.valueOf(deliveryOf(eventId).get("last_response_body"))).contains("SIGNING_SECRET_UNAVAILABLE");
        assertThat(received).isEmpty();

        // merchant-service is back: the retry is signed and goes out
        doReturn(new WebhookTarget(configId, url, secret)).when(merchantClient).getWebhookTarget(merchant, configId);
        bringRetryForward(eventId);
        awaitDelivery(eventId, "DELIVERED");
        assertThat(verifies(received.getFirst(), secret)).isTrue();
    }

    @Test
    void anEventCreatedBeforeDeliveriesWereSignedAtSendTimeStillGoesOutWithItsStoredSignature() {
        UUID id = UUID.randomUUID();
        String body = "{\"id\":\"legacy-1\",\"event\":\"ORDER_CREATED\",\"created\":1,\"payload\":{}}";
        jdbc.update("insert into webhook_event (id, merchant_id, event_type, payload, request_body, event_id, target_url, signature, status, "
                        + "attempts, next_retry_at) values (?::uuid, ?::uuid, 'ORDER_CREATED', '{}'::jsonb, ?, 'legacy-1', ?, 'stored-signature', 'PENDING', 0, now())",
                id.toString(), merchant.toString(), body, url);
        redis.opsForZSet().add("webhook-retry", id.toString(), System.currentTimeMillis());

        awaitDelivery("legacy-1", "DELIVERED");

        Received call = received.getFirst();
        assertThat(call.signature()).isEqualTo("stored-signature");
        assertThat(call.timestamp()).isNull();
        assertThat(call.body()).isEqualTo(body);
    }

    private static feign.FeignException.NotFound notFound() {
        feign.Request request = feign.Request.create(feign.Request.HttpMethod.GET, "http://merchant/internal/merchants/x",
                Map.of(), new byte[0], StandardCharsets.UTF_8, null);
        return new feign.FeignException.NotFound("404", request, null, null);
    }

    @Test
    void aWebhookThatKeepsFailingIsDeadLetteredAndCanBeReplayedOnceTheMerchantFixesIt() throws Exception {
        respondWith.set(500);
        String eventId = publishPaymentEvent();
        awaitDelivery(eventId, "FAILED");

        // six failures in, the seventh attempt is the last: bring the clock forward instead of waiting 36 hours
        jdbc.update("update webhook_event set attempts = 6 where event_id = ?", eventId);
        bringRetryForward(eventId);
        awaitDelivery(eventId, "DEAD");
        assertThat(jdbc.queryForObject("select count(*) from dlq_event where webhook_event_id = ?::uuid", Integer.class,
                deliveryOf(eventId).get("id").toString())).as("a dead-letter row").isPositive();
        int callsBeforeReplay = received.size();

        // the merchant fixes its server and replays it
        respondWith.set(200);
        UUID deliveryId = (UUID) deliveryOf(eventId).get("id");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/webhook-deliveries/" + deliveryId + "/replay")
                        .header("X-Merchant-Id", merchant.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        awaitDelivery(eventId, "DELIVERED");
        assertThat(received.size()).isGreaterThan(callsBeforeReplay);
    }

    @Test
    void anEventForAMerchantWithNoWebhookIsAcknowledgedAndNothingIsDelivered() {
        UUID quiet = UUID.randomUUID();
        when(merchantClient.getActiveConfigsForEvent(eq(quiet), any())).thenReturn(List.of());
        String eventId = UUID.randomUUID().toString();
        kafka.send("payments.events", eventId, Map.of("eventId", eventId, "eventType", "PAYMENT_STATUS_CHANGED",
                "data", Map.of("merchantId", quiet.toString(), "status", "CAPTURED")));

        // a later event for a merchant that does have one is still delivered: the quiet one didn't block the topic
        String next = publishPaymentEvent();
        awaitDelivery(next, "DELIVERED");

        assertThat(deliveryOf(eventId)).isEmpty();
    }

    @Test
    void aTargetThatPointsAtAnInternalMetadataAddressIsBlockedAndNeverCalled() {
        when(merchantClient.getActiveConfigsForEvent(eq(merchant), any()))
                .thenReturn(List.of(new WebhookTarget(UUID.randomUUID(), "http://169.254.169.254/latest/meta-data", secret)));
        String eventId = publishPaymentEvent();

        awaitDelivery(eventId, "FAILED");

        assertThat(String.valueOf(deliveryOf(eventId).get("last_response_body"))).contains("WEBHOOK");
        assertThat(received).isEmpty();
    }
}
