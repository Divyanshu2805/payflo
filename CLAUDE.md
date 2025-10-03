# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

This is a freshly generated Spring Boot skeleton (Spring Initializr output) — currently just
`PayFloApplication.java` and an empty `contextLoads` test. There is no git repository initialized yet,
no controllers, entities, or configuration beyond the default `application.yaml`. Treat any described
"architecture" as what you find as you build it, not an established convention to preserve.

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

## Notes for future structure

- Base package is `com.project.payflo`; keep new code under `src/main/java/com/project/payflo/...`
  following standard Spring Boot layering (e.g. `controller`, `service`, `repository`, `entity`/`model`)
  as those layers get introduced.
- `pom.xml` deliberately blanks out `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`,
  and `<scm>` to override inheritance from `spring-boot-starter-parent` (see `HELP.md`) — this is
  intentional, not an oversight.
