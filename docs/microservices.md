# Microservices (Phase 2)

[← Back to docs index](README.md)

_Last updated: 2026-01-17._

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
| `payment-service` | 8082 | In progress — entities, state machine, transactional outbox |
| `operations-service` | 8084 | Not started |
| `api-gateway-service` | 8080 | Not started |

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
- `idempotency` — `IdempotencyFilter` + `RedisIdempotencyStore` (an `Idempotency-Key` on a write
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
