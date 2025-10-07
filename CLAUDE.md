# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

8 of 15 planned entities are implemented (`common/entity`, `common/enums`, `merchant/entity`,
`payment/entity`) — but there is still no `repository`, `service`, or `controller` layer. The app
compiles and can create its schema, but exposes no APIs yet. Treat any described "architecture" as what
you find as you build it, not an established convention to preserve.

Package layout is domain-oriented, not layered-by-technical-role — `common` (shared `BaseEntity`,
`Money`, enums), `merchant` (Merchant, ApiKey, AppUser, Customer), `payment` (OrderRecord, Payment,
Refund, PaymentTransitionLog) — each anticipating a future microservice boundary, though today it's one
Spring Boot app. Follow this convention for new domains rather than the originally-sketched
`controller`/`service`/`repository` split. `OrderRecord`/`Payment`/`Refund` deliberately store
`merchantId` as a plain UUID with no JPA relationship to `Merchant`, for the same reason.

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

```bash
./mvnw.cmd test
```

Run a single test class:

```bash
./mvnw.cmd test -Dtest=PayFloApplicationTests
```

Run a single test method:

```bash
./mvnw.cmd test -Dtest=PayFloApplicationTests#contextLoads
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

Commit messages for this repo are a single line in semantic-commit format (`type: description`, e.g.
`feat:`, `fix:`, `docs:`, `chore:`), and never mention Claude/AI or add a Co-Authored-By trailer.

## Notes for future structure

- Package layout is domain-oriented (see "Project state" above) — new domains get their own top-level
  package under `com.project.payflo`, with their own `entity` subpackage (and `service`/`repository`/
  `controller` as those get introduced); shared types go in `common`.
- `pom.xml` deliberately blanks out `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`,
  and `<scm>` to override inheritance from `spring-boot-starter-parent` (see `HELP.md`) — this is
  intentional, not an oversight.
