# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

This is still a freshly generated Spring Boot skeleton (Spring Initializr output) — currently just
`PayFloApplication.java` and an empty `contextLoads` test. No controllers, entities, repositories, or
datasource configuration exist beyond the default `application.yaml`. Treat any described "architecture"
as what you find as you build it, not an established convention to preserve.

The domain model (entities, relationships) and full functional/non-functional requirements have been
designed but not implemented — see [docs/requirements.md](docs/requirements.md) for the requirements
and the v1 ER diagram. It's a multi-tenant payments-processing domain: merchant onboarding, order/payment/
refund lifecycles with state machines, card tokenization/vaulting, HMAC-signed webhooks with retry/DLQ,
and nightly settlement — targeting 10k TPS, p99 < 1s, 99.99% availability, and PCI DSS compliance.

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
- `postgresql` (runtime) — this project targets PostgreSQL; there is no datasource configured yet in
  `application.yaml`, so a new `spring.datasource.*` block will be needed before JPA repositories work
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

Commit messages for this repo are a single line, no body, and never mention Claude/AI or add a
Co-Authored-By trailer.

## Notes for future structure

- Base package is `com.project.payflo`; keep new code under `src/main/java/com/project/payflo/...`
  following standard Spring Boot layering (e.g. `controller`, `service`, `repository`, `entity`/`model`)
  as those layers get introduced.
- `pom.xml` deliberately blanks out `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`,
  and `<scm>` to override inheritance from `spring-boot-starter-parent` (see `HELP.md`) — this is
  intentional, not an oversight.
