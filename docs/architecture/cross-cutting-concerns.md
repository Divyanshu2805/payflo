# Cross-Cutting Concerns

Concerns that span every service. Security has its own page: see the [security model](security-model.md).

## Errors

Every error response has one shape — `ErrorResponse { errorCode, errorDescription, timestamp, fieldErrors? }` — produced in one place: `common-lib`'s `GlobalExceptionHandler`, registered in every service through `SharedExceptionAutoConfiguration`. Business-logic failures throw an existing typed exception from `common-lib`'s `exception` package, so they map to a specific status instead of a generic `500`. The gateway answers its own `401` and `429` in the same shape. Full table: [error reference](../api/errors.md).

## Idempotency

A `POST`, `PUT` or `PATCH` carrying `X-Idempotency-Key` is made safe to retry by `common-lib`'s `IdempotencyFilter`, which runs in every business service: the first request claims the key in Redis, a repeat within 24 hours gets the stored response replayed, and only successful responses are stored. `POST /v1/payments` also uses the same header as the payment's own idempotency key, so a retried payment returns the existing attempt instead of creating a second one. Details and limits: [idempotency and rate limits](../api/idempotency-and-rate-limits.md).

## Rate limiting

Only the gateway limits: 200 requests a minute per API key, 600 a minute per merchant for dashboard (JWT) traffic, and 120 a minute per client address on signup and login, each a fixed window in Redis (`common-lib`'s `FixedWindowRateLimiter`) that fails open when Redis is unreachable. The card-testing rules are separate counters where the card is handled. See [idempotency and rate limits](../api/idempotency-and-rate-limits.md#rate-limits).

## Resilience

Every Feign client is wrapped in a Resilience4j circuit breaker and retry, card charging runs behind a thread-pool bulkhead in vault-service, and remote calls are kept out of database transactions. See [service communication](service-communication.md#resilience).

## Events

Services publish events only through a transactional outbox, never by calling Kafka from a request. See [service communication](service-communication.md#events).

## Scheduling

Every background job is a `@Scheduled` method guarded by a ShedLock `@SchedulerLock` with a Redis lock provider, so each runs on exactly one instance however many are deployed. The lock name carries the service's name, so the same job in two services never shares a lock. Each service's `SchedulingConfig` supplies a pooled scheduler (`spring.task.scheduling.pool.size`, 8) so one job's long run doesn't delay another:

| Job | Service | Schedule |
|---|---|---|
| `OutboxPoller` | payment, operations | every 1 s; batches of 500, draining for up to 30 s per run |
| `BankCallbackSimulator` | payment | every `payment.simulator.poll-interval-ms` (1 s); slices of the oldest 500 `AUTHORIZING` payments for up to 30 s per run, resolved `payment.simulator.batch-size` (50) per transaction, `payment.simulator.concurrency` (4) batches at once |
| `WebhookDeliveryScheduler` — deliver due entries | operations | every 200 ms; takes batches of `app.webhook.delivery.poll-batch-size` (100) off the queue, `concurrency` (4) batches at once, each claimed in one transaction, sent concurrently on virtual threads (at most `http-concurrency`, 32, calls in flight) and recorded in one `UPDATE` |
| `WebhookDeliveryScheduler` — reconcile from the database | operations | every 10 s |
| `SettlementEngine` | operations | 23:00 daily (`0 0 23 * * *`), lock held up to 2 h |
| `BankSettlementCallbackSimulator` | operations | every 5 s |
| `SettlementRecoveryJob` | operations | every minute: finishes a settlement interrupted between steps, and fails a transfer the bank never answered |
| `OutboxMaintenance` | payment, operations | every minute: `FAILED` rows back to `PENDING` after 5 minutes; every 5 minutes: published rows older than 7 days deleted, 5,000 at a time |
| `RefundResolver` | payment | every 5 s: the simulated bank's answer to pending refunds |
| `PaymentTimeoutSweeper` | payment | every minute: unanswered and uncaptured payments, and unpaid orders past their expiry |

## Auditing

Every entity extends `BaseEntity`, whose `created_at`/`updated_at` are filled by JPA auditing and `created_by`/`updated_by` by `common-lib`'s `AuditorAwareImpl`: the API key id if the request authenticated with one, else `merchant_id: <uuid>`, else `SYSTEM` for work with no request (schedulers, consumers). Every payment status change is also written to `payment_transition_log` by the state machine.

## Database connections

Open-Session-In-View is off (`spring.jpa.open-in-view: false`), so a service holds a database connection only inside a transaction — never across a remote call. Each service's HikariCP pool is sized in its `config-repo` file and gives up after 5 seconds rather than 30 when exhausted. See [the pitfall](../practices/gotchas/spring-and-jpa.md#open-session-in-view-holds-a-connection-across-remote-calls).

## Schema management

Each service's schema is owned by Flyway: versioned migrations in its `src/main/resources/db/migration`, run on start-up against its own database, with Hibernate set to `validate` so an entity that doesn't match fails the start. A migration is only ever added to. See [schema conventions](../schema/conventions.md).

## Configuration

Every setting lives in `microservices/config-repo/` and is served by config-service; a service's own `application.yaml` only names it and imports the config server. Kubernetes-specific values are profile files (`*-k8s.yaml`) in the same place. See [configuration](../local-development/configuration.md).

## Observability

- **Traces:** every service reports spans to Zipkin through Micrometer Tracing, and the context crosses every hop — gateway proxy, Feign, Kafka — so one request is one trace. See [tracing](../observability/tracing.md).
- **Metrics:** every service exposes `/actuator/prometheus`, with latency histograms on `http.server.requests` so percentiles can be computed across instances. See [metrics](../observability/metrics.md).
- **Logs** go to standard output and carry the trace and span id.
- Each service exposes Actuator's `health`, `info` and `prometheus` endpoints — the gateway on its separate management port `9081`, outside its auth filter. The Kubernetes probes use `/actuator/health`.
- Kafka topics can be browsed in Control Center locally or Kafka UI on Kubernetes.
- Failed webhook deliveries are inspectable in `webhook_event` (last response code and body) and `dlq_event`; failed publishes in `outbox_event.last_error`.
