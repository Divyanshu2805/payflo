# Getting Started

[← Back to docs index](README.md)

Requires Java 25.

Commands below use the Windows wrapper `mvnw.cmd`; on macOS/Linux use `./mvnw` instead.

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
