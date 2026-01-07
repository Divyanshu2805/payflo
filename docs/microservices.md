# Microservices (Phase 2)

[← Back to docs index](README.md)

_Last updated: 2026-01-07._

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
| `config-service` | 8888 | Not started |
| `merchant-service` | 8081 | Not started |
| `vault-service` | 8083 | Not started |
| `payment-service` | 8082 | Not started |
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
