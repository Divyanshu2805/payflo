# Microservices (Phase 2)

[← Back to docs index](README.md)

_Last updated: 2026-02-01._

Phase 2 splits the frozen monolith into independently deployable Spring Boot services along the
domain boundaries it was built around (`common`, `merchant`, `payment`, `vault`, `operations`). The
split lives in [`microservices/`](../microservices) as a Maven multi-module build; the monolith at
the repo root is left untouched as the phase 1 reference.

Every service is its own Spring Boot application with its own `pom.xml`. `microservices/pom.xml` is
only an aggregator (no shared parent), so one command builds everything while each module stays
independently buildable and deployable.

## Module status

| Module | Port | Status |
|---|---|---|
| `common-lib` | — | Done — shared types, auto-configured cross-cutting concerns, inter-service DTOs |
| `discovery-service` | 8761 | Done — Eureka server |
| `config-service` | 8888 | Done — Spring Cloud Config server over `microservices/config-repo` |
| `merchant-service` | 8081 | Done — public auth/API key/webhook APIs plus internal lookup APIs |
| `vault-service` | 8083 | Done — tokenization plus internal, bulkhead-isolated charge API |
| `payment-service` | 8082 | Done — orders, payments, saga, outbox, simulator, internal settlement API |
| `operations-service` | 8084 | Done — Kafka-driven webhooks, nightly settlement with simulated payout callbacks |
| `api-gateway-service` | 8080 | Done — routing, centralized JWT/API-key auth, per-key rate limiting |

## Layout

```
microservices/
├── pom.xml          # aggregator — lists every module
├── mvnw, mvnw.cmd   # one Maven wrapper for the whole build
└── <module>/        # one directory per service / shared library
```

## common-lib

A plain JAR (the Spring Boot repackage step is skipped) that every service depends on — the
extracted equivalent of the monolith's `common` package. Package root
`com.project.payflo.common_lib`.

- `entity` — `BaseEntity` (UUID id, JPA auditing columns) and the `Money` embeddable
  (`amountUnits` in the smallest currency unit + ISO currency). Services extend `BaseEntity` for
  their own entities; nothing in `common-lib` is itself a table.
- `enums` — the full domain vocabulary carried over from the monolith (`PaymentStatus`,
  `PaymentEvent`, `OrderStatus`, `RefundStatus`, `SettlementStatus`, `WebhookEventStatus`,
  `EventAggregateType`, `OutboxStatus`, ...) so every service speaks the same status strings — see
  [Domain Vocabulary](domain-vocabulary.md).
- `exception` — the monolith's exception hierarchy (`ResourceNotFoundException`,
  `DuplicateResourceException`, `InvalidStateTransitionException`,
  `BusinessRuleViolationException`, `IdempotencyConflictException`, `RateLimitException`) plus a
  single `GlobalExceptionHandler` and the shared `ErrorResponse` body. It's registered through
  `SharedExceptionAutoConfiguration` in `META-INF/spring/...AutoConfiguration.imports`, so any
  service that depends on `common-lib` gets identical error shapes and status codes without
  component-scanning the library's packages.
- `context` / `web` — `MerchantContext` (request-scoped merchant id / API key id) and
  `MerchantContextFilter`. In the monolith, the security filters resolved the caller's merchant
  in-process; after the split, **authentication happens once at the API gateway**, which forwards the
  resolved identity as `X-Merchant-Id` / `X-Key-Id` headers. `MerchantContextFilter` reads those
  headers back into `MerchantContext` on every downstream service, so controllers keep calling
  `merchantContext.getMerchantId()` exactly as before. Controlled by
  `app.security.trust-inbound-headers` (default `true`); the gateway itself sets it to `false`.
- `audit` — `AuditorAwareImpl` for `createdBy`/`updatedBy`, reading `MerchantContext` (API key id,
  then `merchant_id: <uuid>`, then `SYSTEM` outside a request) — same rules as the monolith.
- `config/AesEncryptionConfig` + `util` — AES-256-GCM master-key encryptor, `RandomizerUtil`
  (`SecureRandom`-backed keys/secrets), `SignerUtil` (HMAC-SHA256 for webhook signatures).
  `SharedSecurityAutoConfiguration` only creates the encryptor beans a service actually configures
  a key for — `vault.master-key` (vault-service) and `webhook.secret-encryption-key`
  (merchant-service) — so no service holds a key it doesn't need.
- `ratelimit` — the four Redis-backed `RateLimiter` implementations from the monolith (fixed
  window, sliding window, sliding window via Lua, token bucket via Lua), one active at a time via
  `app.rate-limit.method`. In phase 2 only the API gateway enforces rate limits.
- `idempotency` — `IdempotencyFilter` + `RedisIdempotencyStore` (an `X-Idempotency-Key` header on a write
  replays the stored response for 24h). Exposed as a bean; each service that wants it registers the
  filter itself (payment-service does).
- `cache` — `ApiKeyCache` / `RedisApiKeyCache`, so the gateway can authenticate API keys without a
  round trip to merchant-service on every request (5 minute TTL).
- `config/KafkaProperties` — `app.kafka.topics.*`, one topic per `EventAggregateType`
  (`payments.events`, `orders.events`, `refunds.events`, `settlements.events`).
- `dto` — the contracts for service-to-service calls: `FindOrCreateCustomerRequest`
  (payment → merchant), `VaultChargeRequest` / `PaymentProcessorRequest` /
  `PaymentProcessorResponse` (payment → vault), `PaymentSettlementView` / `SettlementBankDetails`
  (operations → payment/merchant), `WebhookTarget` (operations → merchant). Keeping them in one
  library means a contract change is a compile error on both sides, not a runtime surprise.
  `PaymentProcessorResponse` is a sealed interface, so it carries a Jackson type discriminator
  (`@JsonTypeInfo` on a `type` property: `PENDING` / `SUCCESS` / `FAILURE`) — without it, Jackson can't
  pick a concrete record when reading vault-service's reply, and every card payment failed with
  `PAYMENT_GATEWAY_ROUTER_UNREACHABLE` (caught by the saga's compensation step).

## discovery-service

A Netflix Eureka server (`@EnableEurekaServer`, port `8761`). Every other service registers with
it as a Eureka client and resolves its peers by name (`lb://merchant-service`, Feign
`@FeignClient(name = "vault-service")`, ...), so no service hardcodes another's host or port. It
doesn't register with itself (`register-with-eureka: false`, `fetch-registry: false`).

## config-service

A Spring Cloud Config server (`@EnableConfigServer`, port `8888`) using the **native** backend: it
serves YAML straight from [`microservices/config-repo/`](../microservices/config-repo)
(`CONFIG_REPO_PATH`, default `file:../config-repo` relative to the service's working directory), so
config is versioned in this repo alongside the code instead of a separate Git repo with its own
credentials. Each service's own `application.yaml` holds only its name and
`spring.config.import: configserver:${CONFIG_SERVER_URL:http://localhost:8888}`; everything else
(port, datasource, Kafka, Redis, secrets-with-dev-defaults) lives in `config-repo/<service>.yaml`,
with `config-repo/application.yaml` shared by all of them (Eureka URL, Redis, JWT key, actuator
exposure).

## merchant-service

Owns merchants, dashboard users, API keys, customers, and webhook configs — the monolith's
`merchant` domain — with its own database (`payflo_merchant`, `MERCHANT_DB_URL`). Port `8081`.

- Entities carried over unchanged in shape: `Merchant`, `AppUser`, `ApiKey`, `Customer`,
  `MerchantWebhookConfig`, each with a plain `JpaRepository`.
- `POST /v1/auth/signup` / `POST /v1/auth/login` (`AuthController`) — bcrypt-hashed password,
  `JwtUtil.generateAccessToken` issues an HMAC-signed JWT (100-minute expiry) carrying
  `merchant_id` and `role` claims. merchant-service **issues** tokens but doesn't validate them on
  incoming requests any more — that moved to the API gateway, which shares the same `jwt.secret-key`
  through config-service. The monolith's refresh-token flow (`/v1/auth/refresh`, `/v1/auth/logout`)
  wasn't carried over yet.
- `security/WebSecurityConfig` is now just a `PasswordEncoder` bean plus the shared
  `IdempotencyFilter` registration — no `SecurityFilterChain`, because authentication is the
  gateway's job and merchant-service trusts the gateway-set identity headers.
- `/v1/merchants/api-keys` (`ApiKeyController`) — generate (`POST`, secret returned once), list
  (`GET`), revoke (`DELETE /{keyId}`), rotate (`POST /{keyId}/rotate`, 24h grace period on the previous
  secret). Key ids are `fp_<environment>_<random>`. Merchant is taken from `MerchantContext`
  (gateway-set `X-Merchant-Id`), never from the path.
- `/v1/merchants/webhooks` (`WebhookConfigController`) — webhook config CRUD, same contract as the
  monolith: target URL, server-generated signing secret (returned once, AES-encrypted at rest with
  `webhook.secret-encryption-key`), optional event-type filter.
- **Internal API** (`/internal/**`) — not routed by the gateway; only reachable service-to-service
  through Eureka:
  - `GET /internal/api-keys/{keyId}` — API key lookup for the gateway's Basic-auth check (cache miss
    path).
  - `POST /internal/customers/find-or-create` — used by payment-service when an order carries a
    `customer` block (replaces the monolith's in-process `CustomerService.findOrCreate` call).
  - `GET /internal/merchants/{merchantId}/webhook-targets?eventType=...` — subscribed targets with
    decrypted secrets, for operations-service's webhook delivery.
  - `GET /internal/merchants/active-ids`, `GET /internal/merchants/{merchantId}/settlement-bank-details`
    — for operations-service's nightly settlement.

## vault-service

The PCI-scoped service: the only one that ever sees a raw card number, with its own database
(`payflo_vault`, `VAULT_DB_URL`) and the only one configured with `vault.master-key`. Port `8083`.

- `VaultCard` (encrypted PAN + wrapped per-card data key) and `CardToken` (the opaque token handed
  back to merchants), with `customer`/`merchant` stored as plain UUIDs — no FK into
  merchant-service's database.
- `config/VaultEncryptionConfig` — per-card AES-256-GCM data key, wrapped by the shared master-key
  encryptor from `common-lib`.
- `POST /v1/vault/tokenize` (`VaultController`) — validates the card (`@ExpiryYear` rejects past
  years, cardholder name ≥ 3 chars), encrypts and stores the PAN, returns only token, brand, last
  four, and expiry. CVV is validated but never stored.
- `processor/CardPaymentProcessor` — the mock card acquirer (test-PAN scenarios, same as the
  monolith's), moved here so a decrypted PAN never leaves vault-service.
- `POST /internal/vault/charge` (`InternalVaultController`) — payment-service sends a token + amount
  (`VaultChargeRequest`); vault-service decrypts the card and charges it through
  `CardPaymentProcessor`, returning a `PaymentProcessorResponse`. The processor runs behind a
  Resilience4j **thread-pool bulkhead** (`vault-card-processor`, 10–20 threads, queue 50) so a slow
  acquirer can't exhaust vault-service's request threads.

## payment-service

Owns orders, payments, refunds, and the payment state machine — the monolith's `payment` domain —
with its own database (`payflo_payment`, `PAYMENT_DB_URL`). Port `8082`.

- Entities: `OrderRecord`, `Payment`, `Refund`, `PaymentTransitionLog`, `OutboxEvent`.
  `merchantId`/`customerId` stay plain UUIDs (merchant and customer rows live in merchant-service's
  database now, so a FK was never an option). `OrderRepository`/`PaymentRepository` add
  `...ForUpdate` finders (pessimistic write lock) used by the payment saga below.
- `statemachine` — `PaymentStateMachine` (validated `PaymentStatus` × `PaymentEvent` transition
  table, `InvalidStateTransitionException` → `409`) and `PaymentTransitionService` (applies a
  transition and writes a `PaymentTransitionLog` row), unchanged from the monolith.
- `outbox` — the transactional outbox, now doing the job it was built for: `OutboxEventPublisher`
  writes a `PENDING` row in the same transaction as the domain change, `OutboxPoller` publishes
  pending rows to `app.kafka.topics.<aggregate>` and `OutboxResultHandler` marks them
  `PUBLISHED`/`FAILED`. This is how payment-service talks to operations-service — never a direct
  call. With more than one instance of a service running, the poller must only run on one of them at
  a time, so it's wrapped in a **ShedLock** `@SchedulerLock` (`config/SchedularLockConfig`, Redis
  lock provider).
- `POST /v1/orders` (`OrderController`) — if the request carries a `customer` block, the customer is
  resolved first via `CustomerServiceClient` (Feign → merchant-service
  `/internal/customers/find-or-create`, wrapped in a Resilience4j circuit breaker + retry). Only
  after that remote call succeeds does `OrderPersistenceService.persist` open the transaction that
  saves the order and its `ORDER_CREATED` outbox row — keeping a network call out of the DB
  transaction so a slow merchant-service can't hold a connection and row locks open.
- The shared `IdempotencyFilter` is registered here (`config/WebSecurityConfig`), so a retried
  `POST /v1/orders` or `POST /v1/payments` with the same `X-Idempotency-Key` replays the first
  response.
- `OrderMapper` maps the entity's `orderStatus` onto the response's `status` explicitly — without the
  `@Mapping`, MapStruct silently leaves it `null` (the same bug the monolith hit and fixed).
- `processor` — `PaymentProcessor` strategy per `PaymentMethod` (`CardPaymentProcessor`,
  `UpiPaymentProcessor`, `NetBankingPaymentProcessor`) selected by `PaymentProcessorRouter`, with
  the same mock-acquirer test scenarios as the monolith. `WALLET` stays in the shared enum but has no processor or adapter registered in phase 2 yet.
- `gateway` — the `PaymentAdapter` layer routed by `PaymentGatewayRouter`. `UpiPaymentAdapter` and
  `NetBankingAdapter` call the local processors; **`CardPaymentAdapter` calls vault-service**
  (`VaultServiceClient` → `POST /internal/vault/charge`) with only the card token, so payment-service
  stays out of PCI scope. The vault call is wrapped in a Resilience4j circuit breaker + retry
  (`vault-service` instance in `config-repo/payment-service.yaml`: 50% failure threshold over a
  20-call window, 10s open, 3 retries with exponential backoff).
- `POST /v1/payments` / `POST /v1/payments/{paymentId}/capture` (`PaymentController`) — payment
  initiation is split into a small **saga** (`saga/PaymentAuthorizationRecorder`) so no remote call
  runs inside a database transaction:
  1. `recordPayment` — one transaction: lock the order (`SELECT ... FOR UPDATE`), check it's
     payable, create the `Payment`, fire `AUTHORIZE_ATTEMPT`.
  2. Call the gateway adapter (possibly a network hop to vault-service) with no transaction open.
  3. `applyGatewayResult` — second transaction: record the processor reference or the failure, and
     write the `PAYMENT_CREATED` outbox event.
  4. If step 2 throws (vault down, circuit open), `compensateAuthorizationFailure` moves the payment
     to `FAILED` and emits `PAYMENT_AUTHORIZATION_COMPENSATED` instead of leaving it stuck in
     `AUTHORIZING`.
  A repeat call with the same idempotency key returns the existing attempt
  (`findExistingAttempt`) rather than creating a second payment.
- `simulator/BankCallbackSimulator` — **scheduled** in phase 2 (`@Scheduled`, every
  `payment.simulator.poll-interval-ms`, ShedLock-guarded so only one instance runs it): picks up
  payments sitting in `AUTHORIZING` past their simulated bank delay, resolves them per the
  per-method success rate in `payment.simulator.methods.*` (and the global `chaos-mode`), and on
  approval auto-captures them. This closes the monolith's
  [gap 9](gaps.md) — payments now actually reach `AUTHORIZED`/`CAPTURED` end to end, and the order
  moves to `PAID`.
- Internal settlement API (`InternalSettlementController`, backed by `PaymentLookupService`) —
  `GET /internal/payments/unsettled-captured?merchantId=...` returns captured, not-yet-settled
  payments as `PaymentSettlementView`s, and `POST /internal/payments/mark-settled` flags a batch as
  settled once the payout succeeds. operations-service's settlement engine is the only caller.

## operations-service

The asynchronous back office: webhook delivery and nightly settlement, with its own database
(`payflo_operations`, `OPERATIONS_DB_URL`). Port `8084`. Unlike the other business services it
has almost no public API — it's driven by Kafka events and schedules.

- Entities: `WebhookEvent`, `DlqEvent`, `Settlement`, `SettlementPayment` (composite
  `SettlementPaymentId` of settlement id + payment id — the payment id is a plain UUID pointing into
  payment-service's database), and its own `OutboxEvent`.
- `@EnableScheduling` + `@EnableSchedulerLock` with the same Redis-backed ShedLock provider as
  payment-service, so every scheduled job here runs on exactly one instance.
- `outbox` — a second copy of the transactional outbox (settlement events are this service's to
  publish), same publisher/poller/result-handler shape as payment-service's.
- `client` — `MerchantServiceClient` (webhook targets, active merchant ids, settlement bank details)
  and `PaymentServiceClient` (unsettled captured payments, mark-settled), both Feign clients resolved
  through Eureka.
- `webhook` — the monolith's delivery pipeline, now fed across a real service boundary:
  `WebhookKafkaConsumer` listens on `payments.events`/`orders.events`/`refunds.events`/
  `settlements.events` (consumer group `operations-service`, manual ack), asks merchant-service for
  the merchant's subscribed targets (`MerchantServiceClient`), and writes one HMAC-signed
  `WebhookEvent` per target (`X-PayFlo-Signature`). `WebhookDeliveryScheduler` drains the Redis
  sorted-set retry queue (`WebhookRetryQueue`) on virtual threads, `WebhookDeliverExecutor` POSTs
  with a 3s connect / 5s read timeout and fixed backoff (1m → 24h over 7 attempts), and
  `WebhookDlqRecorder` writes a `DlqEvent` once attempts run out. Both scheduler loops are
  ShedLock-guarded.
- `POST /webhook/success` (`DummyMerchantWebhookController`) — a stand-in merchant endpoint that
  always answers `204`, for exercising delivery locally.
- `settlement` — **nightly settlement, new in phase 2** (never built in the monolith):
  - `SettlementEngine` runs at 23:00 (`@Scheduled(cron = "0 0 23 * * *")`, ShedLock, up to 2h),
    fetches active merchant ids from merchant-service, and settles each merchant in parallel on
    virtual threads.
  - `SettlementTransactionExecutor.processForMerchant` pulls the merchant's captured-but-unsettled
    payments from payment-service, computes gross, a 2% fee, 18% GST on the fee, and net, saves a
    `Settlement` (`INITIATED`) with one `SettlementPayment` link per payment, then hands the net
    amount to `BankTransferProcessor` with the merchant's bank details → `TRANSFER_PENDING`.
  - `SettlementIntegrationGateway` wraps every call to payment-/merchant-service in a circuit
    breaker + retry.
  - `BankSettlementCallbackSimulator` (every 5s, ShedLock) stands in for the bank's payout
    callback: it resolves `TRANSFER_PENDING` settlements through
    `SettlementTransactionExecutor.resolveTransfer` — `PROCESSED` (marks the payments settled in
    payment-service and publishes `SETTLEMENT_PROCESSED`) or `FAILED` with a reason
    (`SETTLEMENT_FAILED`). Both events flow through the outbox to the same webhook pipeline, so
    merchants get notified of payouts like any other event.

## api-gateway-service

The single public entry point (port `8080`), built on Spring Cloud Gateway Server **Web MVC** (the
servlet flavor, so it shares the Spring MVC stack and `common-lib` filters with every other service).
Routes live in `config-repo/api-gateway-service.yaml` and resolve targets through Eureka:

| Path | Routed to |
|---|---|
| `/v1/auth/**`, `/v1/merchants/**` | `lb://merchant-service` |
| `/v1/orders/**`, `/v1/payments/**` | `lb://payment-service` |
| `/v1/vault/**` | `lb://vault-service` |
| `/webhook/**` | `lb://operations-service` |

`/internal/**` is deliberately not routed.

**Centralized authentication.** `GatewayAuthFilter` runs before routing on every request that isn't
in `app.security.public-routes` (signup, login, `/webhook/**`, health):

- `Authorization: Bearer <jwt>` → `JwtAuthHandler` verifies the token with the shared
  `jwt.secret-key` (`JwtVerifier`) and forwards `X-Merchant-Id` + `X-User-Role`.
- `Authorization: Basic base64(keyId:secret)` → `ApiKeyAuthHandler` looks the key up in the Redis
  `ApiKeyCache`, falling back to merchant-service (`ApiKeyLookupClient` →
  `GET /internal/api-keys/{keyId}`) on a miss, bcrypt-checks the secret (current or in-grace
  previous), enforces the per-key rate limit (`200`/min, `X-RateLimit-*` headers, `429` +
  `Retry-After` when exceeded), and forwards `X-Merchant-Id`, `X-Key-Id`, `X-Environment`.
- Anything else → `401` with the standard `{errorCode, errorDescription}` body.

Identity headers are applied through `HeaderAugmentingRequestWrapper`, which overrides whatever the
client sent — a caller can't forge `X-Merchant-Id`. The gateway also runs with
`app.security.trust-inbound-headers: false`, so its own `MerchantContextFilter` never reads
client-supplied headers either. Downstream services only trust these headers because they're only
reachable through the gateway; see [Known gaps](gaps.md) for what that assumption still needs.

## Deployment (Kubernetes)

Everything needed to run the services on Kubernetes, built on top of the modules above without
changing how they run locally.

- **Container images via Jib.** Every deployable module (all but `common-lib` and
  `discovery-service`, which isn't used in-cluster) has `jib-maven-plugin` configured — no
  Dockerfiles. Base image `eclipse-temurin:25-jre`, JVM sized from the container limit
  (`-XX:MaxRAMPercentage=75`), image name `${image.prefix}/<module>:<version>` plus `latest`
  (`image.prefix` defaults to `payflo`). Jib isn't bound to a lifecycle phase, so a normal
  `mvnw package` never builds or pushes an image; run `jib:dockerBuild` (local Docker) or
  `jib:build -Dimage.prefix=<registry-user>` (push) explicitly. config-service's image also bakes in
  `microservices/config-repo/` at `/config-repo`.
