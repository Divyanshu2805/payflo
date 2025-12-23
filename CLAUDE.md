# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

All 15 planned entities are now implemented (`common/entity`, `common/enums`, `merchant/entity`,
`payment/entity`, `vault/entity`, `operations/entity`). The `merchant` domain now has a
`repository`/`service`/`controller` slice (signup, login, refresh, logout, API key generate/list/revoke/rotate) and
`payment` has a first one too (order creation, get order by ID, cancel order, list payments for an
order, initiate payment, capture payment) — see "Service/controller layer conventions" —
plus a `resolveAuthorization` method on `PaymentService` with no controller route (internal-only,
meant to be driven by `payment/simulator`, see below); the
`vault` domain now has one too (card tokenization via `POST /v1/vault/tokenize`); `operations`
still has none of that layer yet. Treat any described "architecture" as what you find as you build
it, not an established convention to preserve.

`payment` also has a `gateway`/`gateway/adapter`/`gateway/dto`/`config` set of subpackages
implementing a strategy/adapter pattern for routing payment-method-specific processing
(`PaymentAdapter` interface, one implementation per `PaymentMethod`, selected at runtime by
`PaymentGatewayRouter`) — see "Practices" in [docs/practices.md](docs/practices.md). A
`payment/processor` mirrors the same adapter pattern one layer down — `PaymentProcessor` interface
(`charge()`), one implementation per `PaymentMethod` in `payment/processor/strategy`. All four now
have real mock-acquirer logic recognizing several distinct test scenarios each (test PANs for
card, `bank`/`vpa`/`walletId` sentinel values for the other three — including required-field
validation, e.g. a missing `vpa` now fails with `INVALID_VPA` rather than silently succeeding) —
full table in [docs/api.md](docs/api.md) under `POST /v1/payments`. Routed by
`PaymentProcessorRouter`/`PaymentProcessorConfig` — meant to sit below the adapters as the actual
acquirer-facing call. `NetBankingAdapter`/`UpiPaymentAdapter`/`WalletPaymentAdapter` call through
to it directly (with a `try/catch` around the response-mapping `switch`); `CardPaymentAdapter`
calls through too, but via `vault/service/VaultService.charge` — it decrypts the vaulted card
behind `methodDetails.token` first (same `try/catch`-wrapped pattern), then routes through the same
`PaymentProcessorRouter`. None of the four processors ever return `Success` — deliberately, since
`PaymentServiceImpl`'s `case PaymentResult.Success` branch treats `Success` as an invalid state and
does `return null` (the whole response body), a placeholder from before any processor had real
logic. With nothing producing `Success`, that branch is unreachable dead code, and **all four
methods get a correctly-formed response**: `status: FAILED` with a specific `errorCode` for a
recognized test-failure scenario, `status: AUTHORIZING` (with `processorReference` set) otherwise.
`PaymentMethod` has no fifth value left unregistered, so
`UnsupportedPaymentMethodException`/`400 Bad Request` (below) is currently unreachable through
either router — it stays as the defined behavior for any future method added without adapters to
match.

`PaymentAdapter` also has a `capture(UUID paymentId)` method (routed via
`PaymentGatewayRouter.capture`, exposed as `POST /v1/payments/{paymentId}/capture`) for the
auth-then-capture step. `CardPaymentAdapter`/`NetBankingAdapter`/`UpiPaymentAdapter`/
`WalletPaymentAdapter`'s `capture()` all return a hardcoded `PaymentResult.Success` unconditionally,
not a real (or properly simulated) capture call — moot in practice today anyway, since `capture`
now goes through
`PaymentTransitionService` and requires the payment to already be `AUTHORIZED`, which nothing
currently reaches (see above), so every capture call is rejected with `409
INVALID_STATE_TRANSITION` before any adapter is invoked.

`payment/simulator` is the fix for the "nothing reaches `AUTHORIZED`" gap — a
`BankCallbackSimulator` (`processCallbacks()`, polling `PaymentRepository.
findByStatusAndCreatedAtBefore(AUTHORIZING, ...)`) and `SimulatorConfig`
(`@ConfigurationProperties(prefix = "payment.simulator")`, per-method delay/success-rate plus a
global `ChaosMode`). `PaymentServiceImpl.resolveAuthorization` — what the simulator calls — is
fully implemented: fires `AUTHORIZE_SUCCESS`/`AUTHORIZE_FAIL`, then auto-captures on approval
(fires `CAPTURE_REQUEST`, calls the adapter's `capture()`, fires the matching `CAPTURE_*` event,
sets the order `PAID` on success). **`BankCallbackSimulator.processCallbacks()`'s `@Scheduled` and
`PayFloApplication`'s `@EnableScheduling` were briefly turned on (2025-11-24), then explicitly
turned back off the same day** — both are commented out again. Today's behavior is unchanged from
before that: nothing calls `resolveAuthorization` automatically, every payment sits in
`AUTHORIZING` forever, and `capture` always gets rejected. Turning this on remains a deliberate
"recurring background job against payment data" decision to make explicitly, not a side effect of
other work — it's been flipped both ways once already this session, so double check the current
state (`grep -n "@EnableScheduling\|@Scheduled" PayFloApplication.java BankCallbackSimulator.java`)
rather than assuming from this paragraph alone.

`merchant` also gained a `Customer` slice (`CustomerRepository`/`CustomerService`/
`CustomerServiceImpl.findOrCreate`, no controller — it's driven internally from `OrderServiceImpl`,
not its own endpoint) and a `MerchantWebhookConfig` CRUD slice
(`repository`/`service`/`service.impl`/`mapper`/`controller`, `POST`/`GET`/`GET .../{id}`/
`PUT .../{id}`/`DELETE .../{id}` under `/v1/merchants/webhooks`, on `jwtChain`) — both added
2025-12-16. `POST /v1/orders` now takes an optional `customer` (name/email/phone) and resolves it
through `CustomerService`, storing the result as `OrderRecord.customerId` (still no FK, per
convention). `WebhookConfigServiceImpl` also implements `merchant/api/MerchantWebhookApi`
(`getActiveConfigsForEvent`), the interface `operations/webhook/WebhookKafkaConsumer` (below)
depends on to resolve a merchant's subscribed webhook targets and their (decrypted) secrets —
that's the one seam between the `merchant` and `operations` domains here, and it's one-directional
(`operations` → `merchant`, never the reverse).

`payment` also gained a transactional outbox (`payment/entity/OutboxEvent`,
`payment/outbox/{OutboxEventPublisher,OutboxPoller,OutboxResultHandler}`, added 2025-12-16):
`OrderServiceImpl.create`/`.cancel` and `PaymentServiceImpl.initiate`/`.capture`/
`.resolveAuthorization` insert a `PENDING` `OutboxEvent` row in the same transaction as the domain
write (`ORDER_CREATED`/`ORDER_CANCELLED`/`PAYMENT_CREATED`/`PAYMENT_STATUS_CHANGED`), and a
`@Scheduled` `OutboxPoller` (5s) publishes pending rows to Kafka afterward — so a mid-request crash
can't lose an event the way a direct inline Kafka send could. `operations/webhook` is the consumer
side: `WebhookKafkaConsumer` (`@KafkaListener` on the four event topics) turns each event into one
signed `WebhookEvent` row per subscribed target, `WebhookDeliveryScheduler` drains a Redis
sorted-set retry queue on virtual threads and drives `WebhookDeliverExecutor` (fixed backoff,
1m→24h over 7 attempts), and `WebhookDlqRecorder` records a `DlqEvent` once attempts are exhausted
or a record fails before it's ever persisted. See the Kafka callout right below for why this
exists despite the monolith-first stance, and `docs/gaps.md`'s Known gaps 23–25 (all marked
resolved 2025-12-16) for three bugs found and fixed the same day while documenting this feature: a
missing `GlobalExceptionHandler` entry for `BusinessRuleViolationException`, a dropped
`PaymentTransitionLog.reason` on `AUTHORIZE_FAIL`/`CAPTURE_FAIL` (plus the `Pending`/`null` capture
regression noted above), and a topic-name mismatch between the outbox publisher and this consumer
for `refund`/`settlement` events specifically.

**This is a monolith, on purpose, and stays one for now.** The plan is to build the entire system as a
single Spring Boot application first, then split it into microservices as a separate later phase. The
microservices architecture in [docs/architecture.md](docs/architecture.md#target) is the phase-two destination,
not a description of where the code is heading next.

Practically, that means: **do not add, scaffold, or propose service-splitting infrastructure** — no API
gateway, service discovery/Eureka, config server, or inter-service HTTP/Feign clients — until the user
explicitly says it's time to split. Suggesting them now is premature. Where a design decision would go
one way in a monolith and another in microservices, take the monolith answer and note the future-split
implication in a line rather than building for it.

**Kafka was added 2025-12-16**, despite this file previously listing "Kafka or other broker" alongside
the service-splitting infrastructure above — flagging that explicitly since it reads as a direct
reversal of that guidance and wasn't called out as a deliberate exception when it landed. In practice
it's used entirely *within* the single deployable: a transactional outbox
(`payment/outbox`) inserts domain-event rows in the same DB transaction as the write, a scheduled
poller (`OutboxPoller`) publishes them, and an in-process `@KafkaListener`
(`operations/webhook/WebhookKafkaConsumer`) reads them straight back out to drive webhook delivery —
there's no second service on the other end, no inter-service contract, and nothing here anticipates the
future split any more than any other domain boundary does. Whether "an event bus inside the monolith"
is an acceptable case is a call worth the user making explicitly rather than inferring from the code
that already exists — treat it as decided only once they've said so, not because it compiled and ran.

Package layout is domain-oriented, not layered-by-technical-role — `common` (shared `BaseEntity`,
`Money`, enums, exceptions, `util` — e.g. `RandomizerUtil` for `SecureRandom`-backed key/secret
generation), `merchant` (Merchant, ApiKey, AppUser, Customer, MerchantWebhookConfig), `payment`
(OrderRecord, Payment, Refund, PaymentTransitionLog), `vault` (VaultCard, CardToken), `operations`
(Settlement, SettlementPayment, WebhookEvent, DlqEvent). These are the conventions that keep the
eventual split cheap, and they should keep being honoured: domain packages over layer packages, shared
types in `common`, and `OrderRecord`/`Payment`/`Refund`/`CardToken`/`Settlement`/`SettlementPayment`/
`WebhookEvent`/`DlqEvent` storing cross-domain references (e.g. `merchantId`) as a plain UUID with no
JPA relationship to the owning entity (a real FK can't span two databases, so it would have to be
removed at split time anyway). Follow this convention for new domains rather than the
originally-sketched `controller`/`service`/`repository` split.

Domain vocabulary lives in `common/enums` (16 enums, including `EventAggregateType`/`OutboxStatus`
added 2025-12-16 for the outbox — see below) and is the source of truth for every status,
role, and event value — `PaymentStatus`/`PaymentEvent` in particular define the payment state machine.
Read those before inventing a new status string; they're documented with both state-machine diagrams
under "Domain Vocabulary" in [docs/domain-vocabulary.md](docs/domain-vocabulary.md).
`payment/statemachine/PaymentStateMachine` now encodes a validated transition table for
`PaymentStatus`/`PaymentEvent` (throws `InvalidStateTransitionException` for an undefined pair,
mapped to `409 Conflict` by `GlobalExceptionHandler`). `payment/statemachine/PaymentTransitionService`
wraps it — applies a transition, writes a `PaymentTransitionLog` row, sets `Payment.status` — and
`PaymentServiceImpl.initiate`/`capture` now go through it for their status changes, including a
`CAPTURE_PENDING` self-transition (`CAPTURING` → `CAPTURING`) for a `Pending` capture result — kept
in `CAPTURING` rather than reverting to `AUTHORIZED`, since a genuinely in-flight capture being
retried risks a double capture. A `null` capture result (adapter not implemented) still sets
`status` directly to `AUTHORIZED`, since that's not a real domain event. (Briefly regressed to an
`if/else instanceof` chain with no `Pending`/`null` branch while wiring outbox event publishing on
2025-12-16, caught the same day and restored to the exhaustive `switch` described here — see
`docs/gaps.md`'s Known gap 24 for the writeup.) One
practical consequence: since no payment ever reaches `AUTHORIZED` through any live path
yet (see above — every processor's happy path returns `Pending`, which doesn't advance past
`AUTHORIZING`), every `capture` call currently gets rejected with `409 INVALID_STATE_TRANSITION`
before it can do anything — so this regression is dormant, not yet observable. Its transition table also
revised two things from the diagram's earlier version (both now reflected in `docs/domain-vocabulary.md`): a
failed capture reverts to `AUTHORIZED` rather than terminal `FAILED`, and `REFUND_INIT` now moves
the payment to `PARTIALLY_REFUNDED` itself rather than only touching `RefundStatus`.

`BaseEntity` wires Spring Data JPA auditing annotations (`@CreatedDate`/`@LastModifiedDate`/`@CreatedBy`/
`@LastModifiedBy`). `@EnableJpaAuditing(auditorAwareRef = "auditorAwareImpl")` is on
`PayFloApplication`, so all four now populate: `createdAt`/`updatedAt` automatically, and
`createdBy`/`updatedBy` via `audit/AuditorAwareImpl` — it reads `merchant/security/MerchantContext`
(prefers the API key's `keyId`, falls back to `"merchant_id: <uuid>"` for a JWT-authenticated
request, and to `"SYSTEM"` wrapped in a `try/catch` for anything with no active request, since
`MerchantContext` is `@RequestScope` and would otherwise throw outside one — startup, or a
background job).

The domain model (entities, relationships) and full functional/non-functional requirements have been
designed — see [docs/requirements.md](docs/requirements.md) for the requirements and the v2 ER
diagram (implemented entities now reflect actual schema; the rest are still design-only). It's a
multi-tenant payments-processing domain: merchant onboarding, order/payment/refund lifecycles with state
machines, card tokenization/vaulting, HMAC-signed webhooks with retry/DLQ, and nightly settlement —
targeting 10k TPS, p99 < 1s, 99.99% availability, and PCI DSS compliance. A handful of implemented fields
diverge from that plan (e.g. `OrderRecord` has no `idempotencyKey` yet) — see "Known gaps vs.
requirements" in [docs/schema.md](docs/schema.md) before assuming a requirement is satisfied
just because the entity exists.

Git repo is initialized and pushed to `github.com/Divyanshu2805/payflo` (branch `main`).

- Group/artifact: `com.project:payflo`
- Java version: 25
- Spring Boot: 4.1.0 (via `spring-boot-starter-parent`)

## Commands

Windows shell in this environment is PowerShell; use `mvnw.cmd`.

Local infra (Postgres, Redis, Kafka, Kafka control-center) via `services.docker-compose.yaml`:

```bash
docker compose -f services.docker-compose.yaml up -d
```

```bash
./mvnw.cmd clean compile
```

```bash
./mvnw.cmd spring-boot:run
```

Tests need a running PostgreSQL — `PayFloApplicationTests.contextLoads` is a `@SpringBootTest` that
boots the full context including the datasource. There is no H2 or Testcontainers fallback.

```bash
./mvnw.cmd test -Duser.timezone=Asia/Kolkata
```

**The `-Duser.timezone` flag is currently required.** A plain `./mvnw.cmd test` fails with
`FATAL: invalid value for parameter "TimeZone": "Asia/Calcutta"` — the JVM sends the legacy zone name
and the Postgres server rejects it. Verified: fails without the flag, passes with it. A permanent fix
would be to pin the timezone in `pom.xml` (surefire `argLine`) or `application.yaml` rather than relying
on the flag.

Run a single test class:

```bash
./mvnw.cmd test -Duser.timezone=Asia/Kolkata -Dtest=PayFloApplicationTests
```

Run a single test method:

```bash
./mvnw.cmd test -Duser.timezone=Asia/Kolkata -Dtest=PayFloApplicationTests#contextLoads
```

Package (produces the runnable jar under `target/`):

```bash
./mvnw.cmd clean package
```

## Dependencies already in the POM

- `spring-boot-starter-data-jpa` — JPA/Hibernate persistence
- `spring-boot-starter-webmvc` — Spring MVC (web layer)
- `postgresql` (runtime) — datasource is configured in `application.yaml` (Postgres on `localhost:5432`,
  matching `services.docker-compose.yaml` added 2025-12-16, overridable via `DB_URL`/`DB_USER`/`DB_PASS`
  env vars). `ddl-auto: update` incrementally alters the
  schema on restart instead of dropping it — still not a real migration tool (no version history,
  no rollback), so don't treat it as a substitute for one once that's needed
- `lombok` — annotation processor is wired into both compile and test-compile executions of
  `maven-compiler-plugin` in `pom.xml`; new modules using Lombok don't need extra Maven config
- `mapstruct` (+ `lombok-mapstruct-binding` so Lombok- and MapStruct-generated code compose
  correctly) — entity↔DTO mapping; processor version is pinned via the shared
  `${org.mapstruct.version}` property, wired into the same two `maven-compiler-plugin` executions
  as Lombok
- `spring-boot-starter-validation` — Jakarta Bean Validation (`@NotNull`, `@Email`, `@Size`, etc.)
  on request DTOs, enforced via `@Valid` on controller method parameters
- `jackson-databind` — declared explicitly, though `spring-boot-starter-webmvc` already pulls it in
  transitively; no direct Jackson API usage in the codebase yet that would require the explicit
  declaration
- `spring-boot-starter-security` — originally pulled in only for `spring-security-crypto`'s
  `AesBytesEncryptor`/`KeyGenerators` (card PAN/DEK encryption, the master-key bean now living in
  `common/config/AesEncryptionConfig` — moved there 2025-12-16 and shared with webhook-secret
  encryption below, `vault/config/VaultEncryptionConfig` keeps only the per-card DEK helper); now
  backs two real, enforced
  `merchant/security/WebSecurityConfig` filter chains. **Adding this dependency alone activates
  Spring Boot's default autoconfiguration**, which locks every endpoint behind HTTP Basic with a
  random per-restart password (a `Using generated security password` log line, no matter what)
  unless a `SecurityFilterChain` bean is defined. Only one `SecurityFilterChain` matching "any
  request" is allowed per app; having two unscoped ones (the old placeholder
  `common/config/SecurityConfig` plus a new one) fails startup with
  `UnreachableFilterChainException` — hit and fixed once already. The two chains here are both
  scoped with `.securityMatcher(...)`, never matching "any request", so that's not a risk between
  them: `jwtChain` (`@Order(1)`) covers `JWT_ROUTES` — `/v1/auth/**`, `/v1/merchants/**`,
  `/v1/admin/**`, `/actuator/**`, `/webhook/**` — permitting only `/v1/auth/signup`,
  `/v1/auth/login`, and `/webhook/**`, authenticated otherwise; `apiKeyChain` (`@Order(2)`) covers
  `API_KEY_ROUTES` — `/v1/orders/**`, `/v1/payments/**`, `/v1/vault/**` — authenticated,
  no exceptions. This finally implements the two-mechanism design noted under "Practices" in
  `docs/practices.md`: JWT for the human dashboard, API key (HTTP Basic) for a merchant's own backend
  calling in.
- `io.jsonwebtoken:jjwt-api`/`jjwt-impl`/`jjwt-jackson` (`0.12.6`) — JWT signing/parsing for
  `merchant/security/JwtUtil` (`generateAccessToken`/`verifyAccessToken`, HMAC-signed via
  `jwt.secret-key` in `application.yaml`, a hardcoded dev-only default, 100-minute expiry).
  `POST /v1/auth/login` (`AuthServiceImpl.login`) authenticates via a real
  `AuthenticationManager`/`PasswordEncoder`/`merchant/security/MerchantUserDetailsService` (backed
  by `AppUserRepository`, with `AuthServiceImpl.signup` hashing the password on the way in) and
  returns a `generateAccessToken` JWT; `merchant/security/JwtAuthenticationFilter`
  (`OncePerRequestFilter`, registered on `jwtChain`) reads that token back off the
  `Authorization: Bearer` header on `JWT_ROUTES` requests, verifies it, populates
  `SecurityContextHolder`, and resolves the token's `merchant_id` claim into
  `merchant/security/MerchantContext` (a `@RequestScope` bean). `ApiKeyController` injects
  `MerchantContext` and calls `.getMerchantId()` the same way — its route dropped the
  `{merchantId}` path variable entirely, down to `/v1/merchants/api-keys`.
- **Refresh tokens** (`merchant/entity/RefreshToken`, `merchant/service/RefreshTokenService`, added
  2025-12-08) — a DB-backed, single-use, rotating token, not another JWT: its security is
  `SecureRandom` entropy (`RandomizerUtil.randomBase64(40)`, same length as `ApiKeyServiceImpl`'s
  secret), hashed with plain SHA-256 (`common/util/HashUtil.sha256Hex` — exact-match lookup, unlike
  bcrypt) into `RefreshToken.tokenHash`, so validity is a DB lookup rather than a signature check
  and can be revoked before natural expiry. `POST /v1/auth/login` now also returns a
  `refreshToken`; `POST /v1/auth/refresh` (`AuthServiceImpl.refresh`, its own `@Transactional` —
  `RefreshTokenServiceImpl.rotate`/`.issue` are each independently transactional, so an outer one is
  needed to keep "revoke old, issue new" atomic) exchanges it for a fresh access+refresh pair and
  revokes the one presented; `POST /v1/auth/logout` revokes one directly. Both new routes are in
  `jwtChain`'s `permitAll()` list alongside signup/login — refresh has to work without a currently
  valid access token, that's the entire point. Building this surfaced and fixed a real pre-existing
  gap: `JwtAuthenticationFilter` runs on every `jwtChain` request *including* `permitAll()` ones, so
  a stale `Authorization: Bearer` header attached to a refresh call (exactly what a real frontend
  does) used to leave the response as an untouched empty `200` — `GlobalExceptionHandler` now maps
  `io.jsonwebtoken.JwtException` to a real `401`. See [Known
  gaps](docs/gaps.md) items 16–19.
- **API-key (HTTP Basic) auth** — `merchant/security/ApiKeyAuthenticationFilter`
  (`OncePerRequestFilter`, registered on `apiKeyChain`) is the counterpart for `API_KEY_ROUTES`:
  decodes an `Authorization: Basic base64(keyId:secret)` header, looks up the `ApiKey` by `keyId`
  (`ApiKeyRepository.findByKeyId`, new), and checks the raw secret against `keySecretHash` — or,
  during the 24h post-rotation window (`ApiKey.isInGracePeriod()`), against
  `previousKeySecretHash` too, so a key just rotated doesn't immediately break an in-flight
  integration. On success it populates `SecurityContextHolder` and resolves `MerchantContext`
  (`merchantId` **and** `keyId`, the latter finally giving that once-dead field on `MerchantContext`
  a purpose) from the matched `ApiKey`'s owning merchant. `OrderController`, `PaymentController`,
  and `VaultController` — all on `API_KEY_ROUTES` — read `MerchantContext.getMerchantId()` exactly
  the same way `ApiKeyController` does off `jwtChain`; the controllers don't know or care which
  chain authenticated the request. Practical effect: `/v1/orders`, `/v1/payments`, and
  `/v1/vault/tokenize`, previously fully open, now require a valid API key (not a JWT — a JWT
  presented here wouldn't be read, since this chain's filter only understands `Basic`). A
  malformed/unknown/wrong-secret key throws `org.apache.coyote.BadRequestException`, now mapped by
  `GlobalExceptionHandler` to `401` (`INVALID_API_KEY`). See [Known
  gaps](docs/gaps.md) items 14–15 for what's still open —
  no role/permission distinction within a merchant (any authenticated caller does anything that
  merchant can), and `ApiKey.lastUsedAt` still never gets touched on a successful auth despite
  existing for exactly that purpose.
- `spring-boot-starter-data-redis` (added 2025-12-11) — Redis, configured via `spring.data.redis.*`
  in `application.yaml` (`REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD` env vars, default
  `localhost:6380` since `services.docker-compose.yaml` maps Redis to that host port); `common/config/RedisConfig` exposes a `StringRedisTemplate`. Used by
  `common/rateLimit` (four `RateLimiter` implementations, exactly one active — picked by
  `@ConditionalOnProperty` on `app.rate-limit.method`; **if that property is unset no `RateLimiter`
  bean exists and `ApiKeyAuthenticationFilter` fails to construct**), `common/idempotency`,
  `merchant/cache`, and now `operations/webhook/WebhookRetryQueue` (a sorted set backing the
  webhook delivery retry schedule, see below). See [Known
  gaps](docs/gaps.md) items 20–22 for what's still open.
  Redis is a runtime dependency of the API-key routes; Lettuce connects lazily, so startup itself
  shouldn't need it (not yet verified against a Redis-less run).
- `spring-boot-starter-kafka` (added 2025-12-16) — Kafka, configured via `spring.kafka.*` in
  `application.yaml` (`KAFKA_BROKERS` env var, default `localhost:29092`, matching
  `services.docker-compose.yaml`'s external listener; idempotent producer, manual ack/manual-commit
  consumer, JSON (de)serializers with type headers off). Requires `@EnableScheduling` (see the
  `BankCallbackSimulator` callout above — this is the reason it's back on) for
  `payment/outbox/OutboxPoller`'s publish loop. See the "Kafka was added 2025-12-16" callout in
  Project State above for why this exists despite the monolith-first stance predating it, and
  Known gap 25 for a topic-name mismatch between the outbox publisher and the webhook-delivery
  consumer.
- `spring-boot-starter-data-jpa-test` / `spring-boot-starter-webmvc-test` (test scope)

## Docs to keep in sync

Two places track project state alongside code changes — update them as part of any feature or docs
commit, not as an afterthought:

- `docs/` — tracked, pushed. Living reference docs, one file per topic (tech stack, architecture,
  schema/ER diagram, APIs, practices, …), indexed from `docs/README.md`. The user pulls from these
  for their resume.
- `README.md` — tracked, pushed. GitHub-facing overview; update alongside feature or documentation
  additions.

Which file in `docs/` a change belongs in:

- New or changed endpoint → `docs/api.md`
- New or changed entity or field → `docs/schema.md`
- New enum value or state transition → `docs/domain-vocabulary.md`
- New pattern or cross-cutting convention → `docs/practices.md`
- Structural or deployment change → `docs/architecture.md`
- New dependency or infrastructure piece → `docs/tech-stack.md`
- New or changed build/run/test command → `docs/getting-started.md`
- A gap vs. requirements found or resolved → `docs/gaps.md`
- Anything that changes what's built → `docs/status.md` (see below)

`docs/status.md` holds the **Project Status** table (what's built vs. not) carrying a
"Last updated" date. Refresh that table and its date on every commit that changes what's actually
built — it's the first thing anyone reads to orient, so a stale one is worse than none.

Commit messages for this repo are a single line in semantic-commit format (`type: description`, e.g.
`feat:`, `fix:`, `docs:`, `chore:`), and never mention Claude/AI or add a Co-Authored-By trailer.

## Entity conventions

- Every entity extends `BaseEntity`, uses `@GeneratedValue(strategy = GenerationType.UUID)` for its id,
  and carries `@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder`.
- **Any field with a default value needs `@Builder.Default`.** Without it Lombok silently discards the
  initializer and the builder yields `null` — on a `nullable = false` column that surfaces only as a
  constraint violation at insert. This bit three entities already, and a fourth (`Refund.status`) was
  caught via the compiler warning before it ever ran — the compiler warns, so don't ignore build
  warnings.
- Enums are always `@Enumerated(EnumType.STRING)` with an explicit `length` on the column.
- Money uses the `Money` embeddable (`long` smallest-unit amount + currency), not a bare numeric column.
- Cross-domain references (`merchantId` on payment-domain entities, `customer`/`merchant` on
  `CardToken`, `merchantId` on `Settlement`/`WebhookEvent`/`DlqEvent`, `paymentId` on
  `SettlementPaymentId`) are plain UUIDs with no `@ManyToOne` — see "Project state".

## Service/controller layer conventions

Established by the `merchant` domain's signup slice — follow this pattern for new endpoints rather
than inventing a different shape per domain:

- Request/response DTOs are Java `record`s in `<domain>/dto/request` / `<domain>/dto/response`;
  Jakarta Validation annotations (`@NotNull`, `@Email`, `@Size`, with a `message`) live directly on
  the request record's fields, enforced via `@Valid` on the controller's `@RequestBody` parameter.
- Entity↔DTO mapping goes through a MapStruct interface in `<domain>/mapper`
  (`@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)`), not hand-written mapping
  code. **Read the compiler's "Unmapped target property" warnings** — a source/target field name
  mismatch (e.g. entity `status` vs. DTO `merchantStatus`) is silently left `null` unless given an
  explicit `@Mapping(source = ..., target = ...)`. This already bit the first mapper written.
- Repositories are plain `JpaRepository<Entity, UUID>` interfaces in `<domain>/repository`, using
  derived query methods (`existsByEmail`, `findByEmail`) rather than `@Query`.
- Service layer is an interface in `<domain>/service` plus an implementation in
  `<domain>/service/impl`, constructor-injected via Lombok `@RequiredArgsConstructor`, with
  business methods wrapped in `@Transactional`.
- Controllers live in `<domain>/controller`, routes versioned under `/v1/...`, constructor-injected
  the same way as services.
- Errors go through custom exceptions in `common/exception` (extend `RuntimeException`, carry an
  `errorCode`) caught by the single `GlobalExceptionHandler` (`@RestControllerAdvice`, also in
  `common/exception`), which maps each type to the right HTTP status and a shared `ErrorResponse`
  record. Add a new exception type + handler method there rather than throwing a bare
  `RuntimeException` from a service — that surfaces as an unhandled `500`, not a proper status code.

## Notes for future structure

- Package layout is domain-oriented (see "Project state" above) — new domains get their own top-level
  package under `com.project.payflo`, with their own `entity` subpackage and, once endpoints are
  needed, `repository`/`service`/`service.impl`/`controller`/`dto`/`mapper` subpackages matching the
  `merchant` domain's pattern above; shared types go in `common`.
- `pom.xml` deliberately blanks out `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`,
  and `<scm>` to override inheritance from `spring-boot-starter-parent` (see `HELP.md`) — this is
  intentional, not an oversight.
