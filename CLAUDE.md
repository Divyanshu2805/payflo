# CLAUDE.md

Guidance for AI coding agents working in this repository. It exists so an agent can be productive without re-deriving context each session. Keep it short, and **update it in the same change whenever a convention, command or lesson changes** — a stale working agreement is worse than none.

## Project overview

PayFlo is a payment gateway backend: merchant onboarding and credentials, orders and payments driven by a validated state machine, a PCI-scoped card vault, HMAC-signed webhooks with retries and a dead-letter queue, and nightly settlement. The bank side (acquirer, authorization callback, payouts) is simulated, so every flow runs locally.

- **The system is the microservices build in `microservices/`**: a Spring Cloud Gateway in front of `merchant-service`, `payment-service`, `vault-service` and `operations-service`, each with its own PostgreSQL database, plus `discovery-service` (Eureka), `config-service` (native, over `config-repo/`) and the shared `common-lib`. Java 25, Spring Boot 4.1, Spring Cloud 2025.1.
- **The project is finished as a showcase**: it demonstrates the design against a simulated bank and is not headed for production. Keep changes small, and keep the docs true to the code. Don't assume which service owns a class, endpoint or table — check the docs below.

## Read before acting

These are authoritative and kept current:

| Need | Read |
|---|---|
| Services, module map, request flows, "where do I change X" | [`docs/architecture/`](docs/architecture/README.md) |
| Why the system is shaped as it is | [`docs/architecture/decisions/`](docs/architecture/decisions/README.md) |
| Security boundaries and where they're enforced | [`docs/architecture/security-model.md`](docs/architecture/security-model.md) |
| Entities, tables, enums, state machines | [`docs/schema/`](docs/schema/README.md) |
| Every endpoint, the mock acquirer, idempotency, the error model; the OpenAPI spec | [`docs/api/`](docs/api/README.md) |
| Setup, configuration, troubleshooting, the one-command demo and the dashboard | [`docs/local-development/`](docs/local-development/README.md) |
| The idea, what each decision and optimisation did, the measured numbers | [`docs/project-summary.md`](docs/project-summary.md) |
| What the design accepts and what the project leaves out | [`docs/architecture/trade-offs.md`](docs/architecture/trade-offs.md) |
| Kubernetes manifests, images, cluster configuration | [`docs/deployment/`](docs/deployment/README.md) |
| Tracing, metrics, dashboards | [`docs/observability/`](docs/observability/README.md) |
| The load test and measured results | [`docs/load-testing/`](docs/load-testing/README.md) |
| What it set out to do, and what it measured | [`docs/requirements.md`](docs/requirements.md) |

If a change would make any of these inaccurate, **update that doc in the same change**. Diagrams are generated: edit the owning `d_*.py` in `docs/assets/diagrams/src/`, run `build.py` and `render.py`, and commit the regenerated SVG and PNG.

## Repository structure

```
microservices/
  pom.xml               aggregator only — module list, no shared parent
  common-lib/           shared entities, enums, exceptions + GlobalExceptionHandler, MerchantContext + filter,
                        rate limiters, idempotency filter, API-key cache, Feign DTOs. NOT component-scanned:
                        beans are registered in Shared*AutoConfiguration via META-INF/spring/…imports.
  test-support/         Testcontainers (PostgreSQL, Redis, Kafka) shared by every module's integration tests; no app code
  chaos/                crash_and_outage_test.py: kills a service or stops PostgreSQL/Redis/Kafka under load (results/, logs/ gitignored)
  config-repo/          EVERY service setting: application.yaml (shared), <service>.yaml, *-k8s.yaml overrides.
                        A module's own application.yaml holds only its name and the configserver: import.
  api-gateway-service/  GatewayAuthFilter — the ONLY place requests are authenticated (Bearer JWT or Basic API key,
                        per-key rate limit; /v1/admin/** takes only the operator's X-Admin-Key); forwards X-Merchant-Id /
                        X-Key-Id (or X-Platform-Admin). Routes live in config-repo.
  merchant-service/     merchants, users, API keys, customers, webhook configs; issues JWTs; the audit log and the admin API
  payment-service/      orders, payments, statemachine/, saga/, gateway/ (adapters), processor/, outbox/, simulator/
  vault-service/        tokenization; the only service that decrypts cards (/internal/vault/charge)
  operations-service/   webhook/ (Kafka consumer → Redis retry queue → DLQ; signed per attempt), settlement/, its own outbox/
  discovery-service/, config-service/
  k8s/                  Kustomize manifests + kind-config.yaml; secrets.env is gitignored
  k8s-scaled/           overlay on k8s/: 3 replicas of the gateway and payment-service, and more room for PostgreSQL/Redis/Kafka (a sibling, not inside k8s/: Kustomize forbids it)
  observability/        Zipkin/Prometheus/Grafana: docker-compose.yaml locally, kustomization.yaml (included by k8s/)
  load-test/            JMeter plan + provision_keys.py + run_load_test.py (also reports capture and webhook-SLA from Prometheus) + idempotency_replay_test.py; keys*.csv and results/ are gitignored
  demo/                 demo.py: starts infrastructure and the seven services, seeds a demo merchant, serves the dashboard (logs/, .run/ gitignored)
  dashboard/            a web dashboard over the PUBLIC API only: site/ (static HTML/CSS/JS modules, no build, no dependencies) and serve.py,
                        a loopback server that forwards /v1/** to the gateway. A client, not part of the system: never give it a path around the gateway
  inside each service (com.project.payflo.<module>): entity/ repository/ mapper/ service/ service/impl/
  controller/ dto/ client/ config/   and src/main/resources/db/migration/ (Flyway: V1__baseline.sql, V2__…)
.github/                CI (build, every test, dependency scan) and Dependabot
services.docker-compose.yaml   local PostgreSQL :5432, Redis :6380, Kafka :29092, Control Center :9021
docs/                   documentation — start at docs/README.md
```

## Commands

```bash
docker compose -f services.docker-compose.yaml up -d            # infrastructure (create the 4 databases once — see setup)
cd microservices && ./mvnw clean install -DskipTests            # build every module, install common-lib
cd microservices && ./mvnw verify                               # EVERY test, as CI runs it: unit + integration on real PostgreSQL/Redis/Kafka
                                                                # in containers (needs Docker; nothing else running — no config server, no Eureka)
cd microservices && python demo/demo.py                         # ONE COMMAND: infrastructure, build if needed, 7 services, seed, dashboard on :5173
                                                                # (seed | status | stop | --build | --port-offset 100 when ports are taken)
python docs/api/check_openapi.py                                # the OpenAPI spec must match the controllers (CI runs it)
cd microservices && python chaos/crash_and_outage_test.py       # kill/outage tests against the running stack
cd microservices/<module> && ../mvnw spring-boot:run            # run one service FROM ITS MODULE DIRECTORY
./mvnw -DskipTests jib:dockerBuild -pl <module>                 # container image (see docs/deployment)
kubectl apply -k microservices/k8s                              # deploy to the kind cluster (k8s-scaled for several replicas)
```

On Windows, use `mvnw.cmd`. Start order: discovery → config → the four business services → gateway. config-service resolves `../config-repo` from its working directory, so it must be started from `microservices/config-service`.

## Rules that are easy to break

- **Authentication belongs to the gateway.** No `SecurityFilterChain` in a business service; controllers read the merchant only from `MerchantContext`. `/internal/**` additionally needs the shared `X-Internal-Token` — a new Feign client needs the `InternalAuthFeignConfig` interceptor.
- **No remote call inside `@Transactional`.** See `OrderPersistenceService` and `saga/PaymentAuthorizationRecorder`.
- **Events only through the outbox**; every `@Scheduled` job has a ShedLock `@SchedulerLock`.
- **A sensitive action is audited in the same transaction as the change** (`AuditLogService.record`, no secrets in the details); a new `AuditAction` widens the `audit_log` check constraint in a migration. An admin endpoint lives under `/v1/admin/` and is never reachable with a merchant credential.
- **Payment status changes only through `PaymentTransitionService`.**
- **Card numbers never leave vault-service**; only vault-service is configured with `vault.master-key`.
- **No cross-service foreign keys** — plain UUIDs.
- **Every schema change is a Flyway migration** (`V<next>__….sql` in the owning service's `db/migration`); Hibernate is `ddl-auto: validate` and never changes the schema. Never edit an applied migration. A new enum constant widens its check constraint in the same migration.
- **Nothing is published beyond loopback**: services and infrastructure bind `127.0.0.1` locally (a service trusts `X-Merchant-Id`, so only the gateway may reach it). CI fails on a fixable HIGH/CRITICAL dependency vulnerability: upgrade or pin, don't silence it.
- **New settings go in `config-repo`**, with a `-k8s.yaml` override and a ConfigMap / `secrets.env.example` entry when they differ in-cluster. A new Feign client needs a `url = "${<X>_SERVICE_URI:}"` override for Kubernetes.
- **A new, removed or moved endpoint changes `docs/api/openapi.yaml` in the same change** (hand-written; `check_openapi.py` fails CI otherwise), and its page in `docs/api/`. The dashboard and the Postman collection call the same endpoints: update them when a shape they use changes.
- **The dashboard stays a client of the gateway.** It needs no gateway change: no CORS, no static files on the gateway, no addition to `public-routes`, no loosening of the gateway's CSP. `serve.py` binds loopback only and never forwards `/internal/**`.
- **On Windows, connect to `127.0.0.1`, not `localhost`, from scripts**: the services listen on IPv4 only and each `localhost` call first waits about two seconds on `::1`.
- **A sealed type crossing Feign needs `@JsonTypeInfo`.**
- **Check performance-sensitive changes with the load test**, not just a single request: every bottleneck it has found (gateway proxy pool, Open-Session-In-View, pool sizing, the outbox poller, breaker scope) was invisible one request at a time.
- **The gateway's Actuator is on its management port `9081`**, never the public `8080`.

## Practices

The following are imported in full.

@docs/practices/security-guardrails.md

@docs/practices/coding-conventions.md

@docs/practices/testing.md

@docs/practices/definition-of-done.md

## Known pitfalls

Silent-failure traps this stack has hit, imported in full. Check here first when something "should work" but doesn't.

@docs/practices/gotchas/spring-and-jpa.md

@docs/practices/gotchas/microservices.md

@docs/practices/gotchas/kubernetes.md

## Commits

Commit messages are a single line in semantic-commit format (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, optionally scoped like `docs(api):`), with no mention of Claude or AI and no Co-Authored-By trailer.
