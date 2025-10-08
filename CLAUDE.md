# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

9 of 15 planned entities are implemented (`common/entity`, `common/enums`, `merchant/entity`,
`payment/entity`) — but there is still no `repository`, `service`, or `controller` layer. The app
compiles and can create its schema, but exposes no APIs yet. Treat any described "architecture" as what
you find as you build it, not an established convention to preserve.

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
`Money`, enums), `merchant` (Merchant, ApiKey, AppUser, Customer, MerchantWebhookConfig), `payment`
(OrderRecord, Payment, Refund, PaymentTransitionLog). These are the conventions that keep the eventual
split cheap, and they should keep being honoured: domain packages over layer packages, shared types in
`common`, and `OrderRecord`/`Payment`/`Refund` storing `merchantId` as a plain UUID with no JPA
relationship to `Merchant` (a real FK can't span two databases, so it would have to be removed at split
time anyway). Follow this convention for new domains rather than the originally-sketched
`controller`/`service`/`repository` split.

Domain vocabulary lives in `common/enums` (10 enums) and is the source of truth for every status,
role, and event value — `PaymentStatus`/`PaymentEvent` in particular define the payment state machine.
Read those before inventing a new status string; they're documented with both state-machine diagrams
under "Domain Vocabulary" in [docs/domain-vocabulary.md](docs/domain-vocabulary.md). Note the
transitions are not yet enforced anywhere in code — the enums exist, the validation logic doesn't.

`BaseEntity` wires Spring Data JPA auditing annotations (`@CreatedDate`/`@LastModifiedDate`/`@CreatedBy`/
`@LastModifiedBy`) but `@EnableJpaAuditing` and an `AuditorAware` bean don't exist yet, so `createdBy`/
`updatedBy` currently always come back null — needs wiring up before relying on them.

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
  overridable via `DB_URL`/`DB_USER`/`DB_PASS` env vars). `ddl-auto: create` drops and recreates the
  schema on every restart — fine for local dev, must not ship as-is
- `lombok` — annotation processor is wired into both compile and test-compile executions of
  `maven-compiler-plugin` in `pom.xml`; new modules using Lombok don't need extra Maven config
- `spring-boot-starter-data-jpa-test` / `spring-boot-starter-webmvc-test` (test scope)

## Docs to keep in sync

Two places track project state alongside code changes — update them as part of any feature or docs
commit, not as an afterthought:

- `docs/` — tracked, pushed. Living reference docs, one file per topic (tech stack, architecture,
  schema/ER diagram, APIs, practices, …), indexed from `docs/README.md`. The user pulls from these
  for their resume.
- `README.md` — tracked, pushed. GitHub-facing overview; update alongside feature or documentation
  additions.

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
- Cross-domain references (`merchantId` on payment-domain entities) are plain UUIDs with no
  `@ManyToOne` — see "Project state".

## Notes for future structure

- Package layout is domain-oriented (see "Project state" above) — new domains get their own top-level
  package under `com.project.payflo`, with their own `entity` subpackage (and `service`/`repository`/
  `controller` as those get introduced); shared types go in `common`.
- `pom.xml` deliberately blanks out `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`,
  and `<scm>` to override inheritance from `spring-boot-starter-parent` (see `HELP.md`) — this is
  intentional, not an oversight.
