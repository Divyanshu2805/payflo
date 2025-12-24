# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

PayFlo is a multi-tenant payments-processing backend: merchant onboarding, order/payment/refund
lifecycles driven by state machines, card tokenization (vault), HMAC-signed webhooks with retry/DLQ,
and settlement. Targets: 10k TPS, p99 < 1s, 99.99% availability, PCI DSS.

- Java 25, Spring Boot 4.1.0, Maven (`com.project:payflo`)
- PostgreSQL, Redis, Kafka (local stack in `services.docker-compose.yaml`)
- Lombok, MapStruct, Jakarta Validation, Spring Security, jjwt

**Current phase:** the monolith (phase 1) is feature-frozen. New work targets the microservices
split described in [docs/architecture.md](docs/architecture.md#target); scaffolding service-split
infrastructure (API gateway, service discovery, config server, inter-service clients) is expected
work. What's carried forward unfinished is in [docs/status.md](docs/status.md#phase-1--phase-2-handoff).

## Commands

On Windows use `mvnw.cmd`; on macOS/Linux use `./mvnw`.

```bash
docker compose -f services.docker-compose.yaml up -d
```

```bash
./mvnw.cmd clean compile
```

```bash
./mvnw.cmd spring-boot:run
```

```bash
./mvnw.cmd test -Duser.timezone=Asia/Kolkata
```

```bash
./mvnw.cmd test -Duser.timezone=Asia/Kolkata -Dtest=PayFloApplicationTests#contextLoads
```

```bash
./mvnw.cmd clean package
```

- Tests need a running PostgreSQL (`PayFloApplicationTests` is a full `@SpringBootTest`; no H2 or
  Testcontainers fallback).
- **`-Duser.timezone=Asia/Kolkata` is required** — without it Postgres rejects the JVM's legacy
  `Asia/Calcutta` zone name.
- Default local ports: Postgres `5432`, Redis `6380`, Kafka `29092`. Override via `DB_URL`/`DB_USER`/
  `DB_PASS`, `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD`, `KAFKA_BROKERS`.

## Architecture

Packages under `com.project.payflo` are **domain-oriented**, each a future service boundary:

| Package | Owns |
|---|---|
| `common` | `BaseEntity`, `Money`, all enums, exceptions + `GlobalExceptionHandler`, config (AES, Redis), `rateLimit`, `idempotency`, `util` |
| `merchant` | Merchant, AppUser, ApiKey, RefreshToken, Customer, MerchantWebhookConfig; auth, security filters, API-key cache |
| `payment` | OrderRecord, Payment, Refund, PaymentTransitionLog, OutboxEvent; gateway adapters, processors, state machine, outbox, simulator |
| `vault` | VaultCard, CardToken; card tokenization and AES-GCM encryption |
| `operations` | Settlement, SettlementPayment, WebhookEvent, DlqEvent; webhook delivery pipeline |
| `audit` | `AuditorAwareImpl` for `createdBy`/`updatedBy` |

### Security (two filter chains in `merchant/security/WebSecurityConfig`)

- `jwtChain` (`@Order(1)`): `/v1/auth/**`, `/v1/merchants/**`, `/v1/admin/**`, `/actuator/**`,
  `/webhook/**`. `JwtAuthenticationFilter` reads `Authorization: Bearer`. Public: signup, login,
  refresh, logout, `/webhook/**`.
- `apiKeyChain` (`@Order(2)`): `/v1/orders/**`, `/v1/payments/**`, `/v1/vault/**`.
  `ApiKeyAuthenticationFilter` reads `Authorization: Basic base64(keyId:secret)`, accepts the previous
  secret during the 24h post-rotation grace period, and applies per-key rate limiting.
- Both chains resolve the caller into `MerchantContext` (`@RequestScope`). Controllers read
  `MerchantContext.getMerchantId()` — never take `merchantId` from the path or body.
- Both chains must keep a `.securityMatcher(...)`; two unscoped `SecurityFilterChain` beans fail
  startup.
- Refresh tokens are random, SHA-256-hashed DB rows (single-use, rotated), not JWTs.

### Payment flow

- `PaymentGatewayRouter` picks a `PaymentAdapter` per `PaymentMethod` (card, netbanking, UPI,
  wallet). Adapters call `PaymentProcessorRouter` → `PaymentProcessor.charge()`; card goes through
  `VaultService.charge` first to decrypt the token.
- Processors are mock acquirers: recognized test inputs return `FAILED` with an `errorCode`,
  otherwise `Pending` → payment stays `AUTHORIZING`. They never return `Success`.
- All status changes go through `payment/statemachine/PaymentTransitionService`, which validates
  against `PaymentStateMachine`, writes a `PaymentTransitionLog`, and sets `Payment.status`. An
  undefined transition throws `InvalidStateTransitionException` → `409`.
- `BankCallbackSimulator` resolves `AUTHORIZING` payments via `PaymentService.resolveAuthorization`
  (authorize, then auto-capture). **Its `@Scheduled` is commented out**, so payments stay in
  `AUTHORIZING` and `capture` returns `409`. Enabling it is a deliberate decision — ask first.
- `PaymentStatus`/`PaymentEvent` in `common/enums` define the state machine; read them (and
  [docs/domain-vocabulary.md](docs/domain-vocabulary.md)) before adding any status or event.

### Events and webhooks

- Transactional outbox: order/payment services insert a `PENDING` `OutboxEvent` in the same
  transaction as the domain write; `OutboxPoller` (`@Scheduled`, 5s) publishes to Kafka topics under
  `app.kafka.topics.*`. Never send to Kafka inline from a service.
- `operations/webhook`: `WebhookKafkaConsumer` creates signed `WebhookEvent` rows per subscribed
  target (via `merchant/api/MerchantWebhookApi` — the only `operations` → `merchant` dependency),
  `WebhookDeliveryScheduler` drains a Redis sorted-set retry queue (1m → 24h, 7 attempts), and
  `WebhookDlqRecorder` writes a `DlqEvent` when attempts run out.
- `@EnableScheduling` on `PayFloApplication` is required for the outbox and webhook schedulers.

### Redis

Rate limiting (`common/rateLimit`), idempotency filter for POST/PUT/PATCH (`common/idempotency`),
API-key cache (`merchant/cache`), webhook retry queue. **`app.rate-limit.method` must be set**
(`fixed` by default) — without it no `RateLimiter` bean exists and `ApiKeyAuthenticationFilter`
fails to construct.

## Conventions

### Entities

- Extend `BaseEntity` (JPA auditing fills `createdAt`/`updatedAt`/`createdBy`/`updatedBy`); UUID ids
  via `@GeneratedValue(strategy = GenerationType.UUID)`; `@Getter @Setter @AllArgsConstructor
  @NoArgsConstructor @Builder`.
- **Every field with a default value needs `@Builder.Default`**, or the builder yields `null`.
  Don't ignore the compiler warning.
- Enums: `@Enumerated(EnumType.STRING)` with an explicit column `length`.
- Money: the `Money` embeddable (`long` smallest-unit amount + currency), never a bare numeric.
- **No cross-domain foreign keys**: references across domains (e.g. `merchantId`, `paymentId`) are
  plain UUIDs with no `@ManyToOne`.
- Schema is managed by `ddl-auto: update` — not a migration tool.

### Service / controller layer

- DTOs are Java `record`s in `<domain>/dto/request` and `<domain>/dto/response`; validation
  annotations on request fields, enforced with `@Valid`.
- Mapping via MapStruct interfaces in `<domain>/mapper` (`componentModel = SPRING`). Fix every
  "Unmapped target property" warning — mismatched names are silently left `null`.
- Repositories: `JpaRepository<Entity, UUID>` with derived query methods.
- Services: interface in `<domain>/service`, implementation in `<domain>/service/impl`,
  `@RequiredArgsConstructor`, `@Transactional` business methods.
- Controllers in `<domain>/controller`, routes under `/v1/...`.
- Errors: throw a custom exception from `common/exception` (extends `RuntimeException`, carries an
  `errorCode`) and map it in `GlobalExceptionHandler`. An unmapped exception surfaces as a `500`.
- New domains get their own top-level package with the same subpackage layout; shared types go in
  `common`.
- `pom.xml` intentionally blanks `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`,
  `<scm>` — leave them.

## Docs and commits

Update docs in the same commit as the change:

- New or changed endpoint → `docs/api.md`
- New or changed entity or field → `docs/schema.md`
- New enum value or state transition → `docs/domain-vocabulary.md`
- New pattern or cross-cutting convention → `docs/practices.md`
- Structural or deployment change → `docs/architecture.md`
- New dependency or infrastructure piece → `docs/tech-stack.md`
- New or changed build/run/test command → `docs/getting-started.md`
- A gap vs. requirements found or resolved → `docs/gaps.md`
- Anything that changes what's built → `docs/status.md` (refresh its "Last updated" date)
- Feature-level changes → `README.md`

Check [docs/gaps.md](docs/gaps.md) before assuming a requirement in
[docs/requirements.md](docs/requirements.md) is satisfied.

Commit messages are a single line in semantic-commit format (`feat:`, `fix:`, `docs:`, `chore:`,
`refactor:`), with no Claude/AI mention or Co-Authored-By trailer.
