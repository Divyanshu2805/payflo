# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

PayFlo is a multi-tenant payments-processing backend: merchant onboarding, order/payment/refund
lifecycles driven by state machines, card tokenization (vault), HMAC-signed webhooks with retry/DLQ,
and settlement. Targets: 10k TPS, p99 < 1s, 99.99% availability, PCI DSS.

- Java 25, Spring Boot 4.1.0, Maven (`com.project:payflo`)
- PostgreSQL, Redis, Kafka (local stack in `services.docker-compose.yaml`)
- Lombok, MapStruct, Jakarta Validation, Spring Security, jjwt

**Current phase:** phase 2 — the microservices split under `microservices/` (8 Maven modules,
aggregated by `microservices/pom.xml`). The monolith at the repo root (phase 1) is **frozen**:
don't add features to it; it stays as the reference implementation. Per-service detail is in
[docs/microservices.md](docs/architecture/module-map.md); open work in
[docs/gaps.md](docs/gaps.md#phase-2-microservices). Kubernetes deployment lives in
`microservices/k8s/` (Jib images, Kustomize, kind — see [docs/deployment.md](docs/deployment.md)).
Observability and load testing are deliberately deferred — don't add them unless asked.

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

## Microservices (phase 2)

```bash
cd microservices && ./mvnw.cmd clean install -DskipTests
```

Start order: `discovery-service` (8761) → `config-service` (8888, run from its module dir so
`../config-repo` resolves) → `merchant-service` (8081), `vault-service` (8083), `payment-service`
(8082), `operations-service` (8084) → `api-gateway-service` (8080). Each business service has its
own Postgres database (`payflo_merchant`/`_payment`/`_vault`/`_operations`) on the same local
instance.

- Packages: `com.project.payflo.<module>` (e.g. `payment_service`, `common_lib`). Each module is an
  independent Spring Boot app with its own `pom.xml` (no shared parent); `common-lib` is a plain JAR.
- **Config lives in `microservices/config-repo/<service>.yaml`** (served by config-service's native
  backend); a service's own `application.yaml` only has its name and the `configserver:` import. Add
  new properties to config-repo, not to the module.
- `common-lib` wires cross-cutting beans through `META-INF/spring/...AutoConfiguration.imports`
  (`Shared*AutoConfiguration`) — register new shared beans there rather than relying on component
  scan.
- **Auth is done only at the gateway** (`GatewayAuthFilter`: Bearer JWT or Basic API key, per-key rate
  limit). It forwards `X-Merchant-Id`/`X-Key-Id`; `common-lib`'s `MerchantContextFilter` rebuilds
  `MerchantContext` downstream. Services have no `SecurityFilterChain`. Controllers still read
  `MerchantContext.getMerchantId()`.
- Service-to-service: Feign clients by Eureka name, under `/internal/**` (never routed by the
  gateway), wrapped in Resilience4j `@CircuitBreaker`/`@Retry` (instances configured in config-repo).
  Contracts are DTOs in `common-lib/dto`; a sealed interface crossing Feign needs `@JsonTypeInfo`.
- Keep remote calls out of `@Transactional` methods (see `OrderPersistenceService` and
  `saga/PaymentAuthorizationRecorder` for the pattern).
- Async between services only via the transactional outbox → Kafka. Every `@Scheduled` job needs a
  ShedLock `@SchedulerLock`.
- Kubernetes: `SPRING_PROFILES_ACTIVE=k8s` (from `k8s/infra/configmap.yaml`) switches Eureka off and
  picks up `config-repo/*-k8s.yaml`; Feign clients take `*_SERVICE_URI` overrides. config-service must
  run with `native,k8s`. Add any new env var to the ConfigMap (or `secrets.env.example` if secret)
  and any new service to `k8s/services/` + `kustomization.yaml`.
- `BankCallbackSimulator` **is** scheduled in payment-service (unlike the monolith) — payments reach
  `CAPTURED`.

## Monolith architecture (phase 1, frozen)

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
  [docs/domain-vocabulary.md](docs/schema/enums.md)) before adding any status or event.

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
- Anything in `microservices/` → `docs/microservices.md` (per-service section + module status table)
- A gap vs. requirements found or resolved → `docs/gaps.md`
- Anything that changes what's built → `docs/status.md` (refresh its "Last updated" date)
- Feature-level changes → `README.md`

Check [docs/gaps.md](docs/gaps.md) before assuming a requirement in
[docs/requirements.md](docs/requirements.md) is satisfied.

Commit messages are a single line in semantic-commit format (`feat:`, `fix:`, `docs:`, `chore:`,
`refactor:`), with no Claude/AI mention or Co-Authored-By trailer.
