# Getting Started

[← Back to docs index](README.md)

Requires Java 25. There are two builds in this repo: the phase 1 monolith at the repo root, and the
phase 2 microservices under `microservices/` — see [Running the microservices](#running-the-microservices)
below for the latter.

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

## Running the microservices

The phase 2 services live under `microservices/` and have their own Maven wrapper and aggregator
`pom.xml`. They use the same local Postgres/Redis/Kafka as the monolith
(`services.docker-compose.yaml` at the repo root), but each business service gets its **own
database** — create them once:

```bash
docker exec -it pgvector-payflo psql -U user -d payflo-db -c "CREATE DATABASE payflo_merchant" -c "CREATE DATABASE payflo_payment" -c "CREATE DATABASE payflo_vault" -c "CREATE DATABASE payflo_operations"
```

Build every module (`common-lib` is built first, as part of the same reactor):

```bash
cd microservices && ./mvnw.cmd clean install -DskipTests
```

Start the services **in this order**, each from its own module directory (config-service resolves
`../config-repo` relative to its working directory, and every other service pulls its config from
config-service on startup):

1. `discovery-service` — Eureka, `http://localhost:8761`
2. `config-service` — `http://localhost:8888` (check with `curl localhost:8888/payment-service/default`)
3. `merchant-service` (8081), `vault-service` (8083), `payment-service` (8082),
   `operations-service` (8084) — any order
4. `api-gateway-service` — `http://localhost:8080`, the only port clients should call

```bash
cd microservices/discovery-service && ../mvnw.cmd spring-boot:run
```

(and likewise for each module.) Every setting — ports, datasource URLs, Kafka, Redis, secrets with
dev-only defaults — is in `microservices/config-repo/<service>.yaml` and can be overridden with the
env vars named there (`MERCHANT_DB_URL`, `KAFKA_BROKERS`, `REDIS_HOST`, `JWT_SECRET`,
`VAULT_MASTER_KEY`, ...). If port 8080 is taken locally, pass `--server.port=<port>` to the gateway.

A quick end-to-end check through the gateway: `POST /v1/auth/signup` → `POST /v1/auth/login` →
`POST /v1/merchants/api-keys` (Bearer token) → `POST /v1/orders` and `POST /v1/payments` (Basic
`keyId:secret`). Within a few seconds the bank callback simulator moves the payment from
`AUTHORIZING` to `CAPTURED`.

The per-module `*ApplicationTests.contextLoads` tests boot the full context, including the
`configserver:` import, so they need discovery-service, config-service, and the infra running.

To run the same services on Kubernetes instead, see [Deployment](deployment.md).

Load tests and observability (tracing/metrics dashboards)
are deliberately not part of the microservices build yet.
