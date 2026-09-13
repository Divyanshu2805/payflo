# Cross-Cutting Concerns

Concerns that span every service. Security has its own page: see the [security model](security-model.md).

## Errors

Every error response has one shape — `ErrorResponse { errorCode, errorDescription, timestamp, fieldErrors? }` — produced in one place: `common-lib`'s `GlobalExceptionHandler`, registered in every service through `SharedExceptionAutoConfiguration`. Business-logic failures throw an existing typed exception from `common-lib`'s `exception` package, so they map to a specific status instead of a generic `500`. The gateway answers its own `401` and `429` in the same shape. Full table: [error reference](../api/errors.md).

## Idempotency

A `POST`, `PUT` or `PATCH` carrying `X-Idempotency-Key` is made safe to retry by `common-lib`'s `IdempotencyFilter`, which every business service registers: the first request claims the key in Redis, a repeat within 24 hours gets the stored response replayed, and only successful responses are stored. `POST /v1/payments` also uses the same header as the payment's own idempotency key, so a retried payment returns the existing attempt instead of creating a second one. Details and limits: [idempotency and rate limits](../api/idempotency-and-rate-limits.md).

## Rate limiting

Only the gateway limits, and only API-key traffic: 200 requests per minute per key by default. The algorithm is a strategy — one of four Redis-backed `RateLimiter` implementations in `common-lib` (fixed window, sliding window, sliding window in Lua, token bucket in Lua) — chosen by `app.rate-limit.method`, so swapping it is a configuration change.

## Resilience

Every Feign client is wrapped in a Resilience4j circuit breaker and retry, card charging runs behind a thread-pool bulkhead in vault-service, and remote calls are kept out of database transactions. See [service communication](service-communication.md#resilience).

## Events

Services publish events only through a transactional outbox, never by calling Kafka from a request. See [service communication](service-communication.md#events).

## Scheduling

Every background job is a `@Scheduled` method guarded by a ShedLock `@SchedulerLock` with a Redis lock provider, so each runs on exactly one instance however many are deployed:

| Job | Service | Schedule |
|---|---|---|
| `OutboxPoller` | payment, operations | every 5 s |
| `BankCallbackSimulator` | payment | every `payment.simulator.poll-interval-ms` (5 s) |
| `WebhookDeliveryScheduler` — deliver due entries | operations | every 1 s, deliveries on virtual threads |
| `WebhookDeliveryScheduler` — reconcile from the database | operations | every 10 s |
| `SettlementEngine` | operations | 23:00 daily (`0 0 23 * * *`), lock held up to 2 h |
| `BankSettlementCallbackSimulator` | operations | every 5 s |

## Auditing

Every entity extends `BaseEntity`, whose `created_at`/`updated_at` are filled by JPA auditing and `created_by`/`updated_by` by `common-lib`'s `AuditorAwareImpl`: the API key id if the request authenticated with one, else `merchant_id: <uuid>`, else `SYSTEM` for work with no request (schedulers, consumers). Every payment status change is also written to `payment_transition_log` by the state machine.

## Schema management

Each service's tables are created and altered by Hibernate (`ddl-auto: update`). There is no migration tool, no version history and no rollback; a removed column is never dropped. See [schema conventions](../schema/conventions.md) and [known gaps](../gaps.md).

## Configuration

Every setting lives in `microservices/config-repo/` and is served by config-service; a service's own `application.yaml` only names it and imports the config server. Kubernetes-specific values are profile files (`*-k8s.yaml`) in the same place. See [configuration](../local-development/configuration.md).

## Observability

- Logs go to standard output; there is no log shipping, tracing or metrics pipeline yet.
- Each service exposes Actuator's `health` and `info` endpoints; the Kubernetes probes use `/actuator/health`.
- Kafka topics can be browsed in Control Center locally or Kafka UI on Kubernetes.
- Failed webhook deliveries are inspectable in `webhook_event` (last response code and body) and `dlq_event`; failed publishes in `outbox_event.last_error`.
