# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

All 15 planned entities are now implemented (`common/entity`, `common/enums`, `merchant/entity`,
`payment/entity`, `vault/entity`, `operations/entity`). The `merchant` domain now has a
`repository`/`service`/`controller` slice (signup, API key generate/list/revoke/rotate) and
`payment` has a first one too (order creation, get order by ID, cancel order, list payments for an
order, initiate payment) — see "Service/controller layer conventions"; the
`vault` and `operations` domains still have none of that layer yet. Treat any described
"architecture" as what you find as you build it, not an established convention to preserve.

`payment` also has a `gateway`/`gateway/adapter`/`gateway/dto`/`config` set of subpackages
implementing a strategy/adapter pattern for routing payment-method-specific processing
(`PaymentAdapter` interface, one implementation per `PaymentMethod`, selected at runtime by
`PaymentGatewayRouter`) — see "Practices" in [docs/practices.md](docs/practices.md). The
adapters are stubs (`// TODO`, return `null`); no real or mock acquirer integration exists yet. A
`payment/processor` mirrors the same adapter pattern one layer down — `PaymentProcessor` interface
(`charge()`), one implementation per `PaymentMethod` in `payment/processor/strategy`
(`CardPaymentProcessor` has real mock-acquirer logic with test PANs; `NetBankingPaymentProcessor`/
`UpiPaymentProcessor` are stubs), routed by `PaymentProcessorRouter`/`PaymentProcessorConfig` —
meant to sit below the adapters as the actual acquirer-facing call. `NetBankingAdapter`/
`UpiPaymentAdapter` now call through to it (with a `try/catch` around the response-mapping
`switch`), but since the processor strategies they hit are still stubs, both always resolve to
`PaymentResult.Failure`. `CardPaymentAdapter` still doesn't call anything and stays a stub, even
though `CardPaymentProcessor` one layer down already has real logic — so no payment method
currently reaches a successful result through `POST /v1/payments`.

**This is a monolith, on purpose, and stays one for now.** The plan is to build the entire system as a
single Spring Boot application first, then split it into microservices as a separate later phase. The
microservices architecture in [docs/architecture.md](docs/architecture.md#target) is the phase-two destination,
not a description of where the code is heading next.

Practically, that means: **do not add, scaffold, or propose service-splitting infrastructure** — no API
gateway, service discovery/Eureka, config server, Kafka or other broker, per-service databases, or
inter-service HTTP/Feign clients — until the user explicitly says it's time to split. Suggesting them
now is premature. Where a design decision would go one way in a monolith and another in microservices,
take the monolith answer and note the future-split implication in a line rather than building for it.

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

Domain vocabulary lives in `common/enums` (13 enums) and is the source of truth for every status,
role, and event value — `PaymentStatus`/`PaymentEvent` in particular define the payment state machine.
Read those before inventing a new status string; they're documented with both state-machine diagrams
under "Domain Vocabulary" in [docs/domain-vocabulary.md](docs/domain-vocabulary.md). Note the
transitions are not yet enforced anywhere in code — the enums exist, the validation logic doesn't.

`BaseEntity` wires Spring Data JPA auditing annotations (`@CreatedDate`/`@LastModifiedDate`/`@CreatedBy`/
`@LastModifiedBy`). `@EnableJpaAuditing` is now on `PayFloApplication`, so `createdAt`/`updatedAt`
populate correctly — but there's still no `AuditorAware` bean, so `createdBy`/`updatedBy` still
always come back null. Wiring one up needs something to source the current user from, which needs
auth to exist first.

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
- `postgresql` (runtime) — datasource is configured in `application.yaml` (Postgres on `localhost:9000`,
  overridable via `DB_URL`/`DB_USER`/`DB_PASS` env vars). `ddl-auto: update` incrementally alters the
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
  constraint violation at insert. This bit three entities already; the compiler warns, so don't ignore
  build warnings.
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
