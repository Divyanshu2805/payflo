# Flow: Webhook Delivery

How PayFlo tells a merchant's own system that something happened. A domain event on Kafka becomes one signed delivery per subscribed endpoint, retried on a fixed schedule for up to 24 hours and dead-lettered after that. It runs in operations-service.

![Webhook delivery](../../assets/diagrams/flow-webhook-delivery.png)

All paths below are under `operations-service/src/main/java/com/project/payflo/operations_service/`.

## From event to delivery row

1. **`webhook/WebhookKafkaConsumer`** listens on `payments.events`, `orders.events`, `refunds.events` and `settlements.events` (consumer group `operations-service`, manual acknowledgement). It is a **batch listener**: it gets up to `max-poll-records` (500) records at a time and turns all of them into deliveries in one go (steps 2 to 4 below, once per poll rather than once per event).
2. It asks merchant-service which endpoints want this event — `webhook/WebhookTargetCache` → `client/MerchantServiceClient` → `GET /internal/merchants/{merchantId}/webhook-targets?eventType=…`. The answer is remembered in memory for `app.webhook.target-cache-ttl-seconds` (30), so a new, changed or disabled config takes up to that long to apply; a call per event capped the consumer at a few hundred events a second. A config subscribes to a comma-separated list of event types, or to everything when the list is blank or `ALL`. The response carries each target's URL and its **decrypted** signing secret.
3. It builds the delivery body **once** — `{ id, event, created, payload }`, where `id` is the outbox row's id carried in the Kafka envelope (`eventId`), the same however often the event is published or delivered — serializes it to a string, and for each target signs **that string** with HMAC-SHA256 (`common-lib`'s `SignerUtil`, that target's secret). It saves a `WebhookEvent` in `PENDING` holding the string (`request_body`), the signature, the event id and the target URL (copied in, so a later config change never rewrites history). Delivery sends the stored string byte for byte, so the signature is over exactly what the merchant receives; it used to be computed over one serialization and sent as another, which `Map.of`'s per-JVM ordering could make differ.
   It also keeps the envelope's `occurredAt` (when the change was committed, stamped by the outbox poller) in `event_occurred_at`, which is where the delivery-latency clock starts.
4. It adds the events to the Redis sorted set `webhook-retry` (`webhook/WebhookRetryQueue`), scored by when they are due, in one `ZADD`, then acknowledges the Kafka records.

The deliveries of **every record in a poll are saved in one transaction** (`saveAll`), so a redelivery can't create a second copy of a delivery that already had one, and a poll costs one commit instead of one per event (one commit per event capped the consumer at a few hundred events a second, and a single partition of 1,000 events a second fell steadily behind under load). A failure to queue in Redis doesn't fail the poll: the events are saved, and the reconciler below queues them once they are overdue.

What happens when processing fails depends on what failed (`WebhookKafkaConsumer.isTransient`):

- **A dependency is down** — the database, Redis, or merchant-service (a connection error, a timeout, a `5xx`, an open circuit breaker). Nothing of the poll is acknowledged: `nack` seeks back to its first unfinished record and Kafka redelivers it after 5 seconds, repeatedly, until the dependency is back. Acknowledging a later record would commit past it and lose it, which is what used to happen.
- **The record itself is bad** — a malformed payload or an unparseable id, or a value the database refuses. The batch can't be saved as it is, so the records are handled **one at a time**: the bad one is written to the DLQ with `WebhookDlqRecorder`, with no event link, and the rest go through as before.

## Delivering

`webhook/WebhookDeliveryScheduler` drains the queue every 200 ms (ShedLock-guarded): a run keeps taking due entries off it, up to `app.webhook.delivery.poll-batch-size` (200) at a time, for up to 5 seconds, and hands each to its own **virtual thread**, at most `app.webhook.delivery.concurrency` (8) at once. It only takes as many as it has free workers for, so an event is never taken off the queue to wait in memory (where a crash would hide it until the reconciler found it). It used to take one batch of 100 a second, which capped delivery at about 100 events a second however idle the machine was. `webhook/WebhookDeliverExecutor` then delivers each one in three steps, **with no database transaction open during the HTTP call** — a slow merchant endpoint holds a thread, not a connection, so it can't starve everyone else:

1. **Claim** (`webhook/WebhookDeliveryRecorder.claim`, a short transaction): lock the row, skip it if it is `DELIVERED`, `DEAD`, or not yet due, count the attempt, and set `next_retry_at` two minutes ahead as a **lease**. A duplicate queue entry, or the reconciler, finds it not due and backs off, so one event is never delivered twice at once.
2. **Sign and POST.** The URL is validated again first ([webhook targets](../security-model.md#webhook-targets)). Then `webhook/WebhookSecretResolver` asks merchant-service for the config's current secret (`GET /internal/merchants/{merchantId}/webhook-targets/{configId}`, behind the `merchant-service` circuit breaker and retry, remembered in memory for 30 s), the attempt gets the current time as `X-PayFlo-Timestamp`, and `WebhookSignatures.sign` computes the signature over `<timestamp>.<stored body>`, sent in `X-PayFlo-Signature`. The stored body is POSTed byte for byte with the event id in `X-PayFlo-Event-Id`, a 3-second connect and 5-second read timeout. If merchant-service can't be asked the attempt fails (`SIGNING_SECRET_UNAVAILABLE`) and is retried on the usual schedule; if the merchant has deleted the config (`404`) the event is dead-lettered at once (`WEBHOOK_CONFIG_DELETED`), since no retry can help. Events created before signing moved to send time have no `config_id` and go out with the signature stored on them, without a timestamp.
3. **Record** the outcome (a second short transaction): on a `2xx`, `DELIVERED` with `delivered_at`; on anything else, `last_response_code` / `last_response_body` are saved, the status becomes `FAILED`, and the next attempt is scheduled after **1 min, 5 min, 30 min, 2 h, 8 h, then 24 h**; on the **seventh** failed attempt the event becomes `DEAD` and a `DlqEvent` with the final error and the payload is written in the same transaction.

The Redis queue is only a fast path; the database is the truth. A second scheduler pass every 10 seconds (`reconcileFromDatabase`) re-queues any **`PENDING` or `FAILED`** row whose `next_retry_at` is more than 30 seconds in the past with no queue entry — a lost enqueue, a Redis restart, or a crash mid-delivery once its lease has run out. (It used to cover only `PENDING`, so a `FAILED` event that lost its queue entry was never retried or dead-lettered.) Delivery is at-least-once: a crash after the merchant answered but before the result was saved means one more delivery, so receivers should de-duplicate.

## Replay

`POST /v1/webhook-deliveries/{id}/replay` ([API](../../api/webhook-deliveries.md)) puts a `DEAD`, `FAILED` or `DELIVERED` event back to `PENDING` with fresh attempts, due now, and queues it. It keeps the stored body, which is signed afresh when it is sent, so a receiver sees the same event id. A dead-lettered event's `DlqEvent` is marked replayed.

## Trying it locally

operations-service exposes `POST /webhook/success`, a stand-in merchant endpoint that always answers `204`. Register `http://localhost:8084/webhook/success` (or `http://operations-service:8084/webhook/success` on Kubernetes) as a webhook target to watch deliveries succeed.

## Related

- [Webhook configs](../../api/webhook-configs.md) — how merchants register endpoints.
- [Delivery status](../../schema/enums.md#delivery-status) and the [operations-service data model](../../schema/operations-service.md).
- [Service communication → events](../service-communication.md#events).
