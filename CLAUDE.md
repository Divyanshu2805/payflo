# CLAUDE.md

Guidance for AI coding agents working in this repository. It exists so an agent can be productive without re-deriving context each session. Keep it short, and **update it in the same change whenever a convention, command or lesson changes** — a stale working agreement is worse than none.

## Project overview

PayFlo is a payment gateway backend: merchant onboarding and credentials, orders and payments driven by a validated state machine, a PCI-scoped card vault, HMAC-signed webhooks with retries and a dead-letter queue, and nightly settlement. The bank side (acquirer, authorization callback, payouts) is simulated, so every flow runs locally.

- **The system is the microservices build in `microservices/`**: a Spring Cloud Gateway in front of `merchant-service`, `payment-service`, `vault-service` and `operations-service`, each with its own PostgreSQL database, plus `discovery-service` (Eureka), `config-service` (native, over `config-repo/`) and the shared `common-lib`. Java 25, Spring Boot 4.1, Spring Cloud 2025.1.
- **The monolith at the repository root is frozen** — the phase 1 reference the services were extracted from. Don't add features to it. Don't assume which service owns a class, endpoint or table — check the docs below.

## Read before acting

These are authoritative and kept current:

| Need | Read |
|---|---|
| Services, module map, request flows, "where do I change X" | [`docs/architecture/`](docs/architecture/README.md) |
| Why the system is shaped as it is | [`docs/architecture/decisions/`](docs/architecture/decisions/README.md) |
| Security boundaries and where they're enforced | [`docs/architecture/security-model.md`](docs/architecture/security-model.md) |
| Entities, tables, enums, state machines | [`docs/schema/`](docs/schema/README.md) |
| Every endpoint, the mock acquirer, idempotency, the error model | [`docs/api/`](docs/api/README.md) |
| Setup, configuration, troubleshooting, the monolith | [`docs/local-development/`](docs/local-development/README.md) |
| Constraints, trade-offs, what isn't built yet | [`docs/known-gaps/`](docs/gaps.md) |
| Kubernetes manifests, images, cluster configuration | [`docs/deployment/`](docs/deployment.md) |
| Design targets | [`docs/requirements.md`](docs/requirements.md) — check known gaps before assuming one is met |

If a change would make any of these inaccurate, **update that doc in the same change**. Diagrams are generated: edit the owning `d_*.py` in `docs/assets/diagrams/src/`, run `build.py` and `render.py`, and commit the regenerated SVG and PNG.

## Repository structure

```
microservices/
  pom.xml               aggregator only — module list, no shared parent
  common-lib/           shared entities, enums, exceptions + GlobalExceptionHandler, MerchantContext + filter,
                        rate limiters, idempotency filter, API-key cache, Feign DTOs. NOT component-scanned:
                        beans are registered in Shared*AutoConfiguration via META-INF/spring/…imports.
  config-repo/          EVERY service setting: application.yaml (shared), <service>.yaml, *-k8s.yaml overrides.
                        A module's own application.yaml holds only its name and the configserver: import.
  api-gateway-service/  GatewayAuthFilter — the ONLY place requests are authenticated (Bearer JWT or Basic API key,
                        per-key rate limit); forwards X-Merchant-Id / X-Key-Id. Routes live in config-repo.
  merchant-service/     merchants, users, API keys, customers, webhook configs; issues JWTs
  payment-service/      orders, payments, statemachine/, saga/, gateway/ (adapters), processor/, outbox/, simulator/
  vault-service/        tokenization; the only service that decrypts cards (/internal/vault/charge)
  operations-service/   webhook/ (Kafka consumer → Redis retry queue → DLQ), settlement/, its own outbox/
  discovery-service/, config-service/
  k8s/                  Kustomize manifests + kind-config.yaml; secrets.env is gitignored
  inside each service (com.project.payflo.<module>): entity/ repository/ mapper/ service/ service/impl/
  controller/ dto/ client/ config/
src/, pom.xml           the frozen monolith (com.project.payflo)
services.docker-compose.yaml   local PostgreSQL :5432, Redis :6380, Kafka :29092, Control Center :9021
docs/                   documentation — start at docs/README.md
```

## Commands

```bash
docker compose -f services.docker-compose.yaml up -d            # infrastructure (create the 4 databases once — see setup)
cd microservices && ./mvnw clean install -DskipTests            # build every module, install common-lib
cd microservices/<module> && ../mvnw spring-boot:run            # run one service FROM ITS MODULE DIRECTORY
./mvnw -DskipTests jib:dockerBuild -pl <module>                 # container image (see docs/deployment)
kubectl apply -k microservices/k8s                              # deploy to the kind cluster
```

On Windows, use `mvnw.cmd`. Start order: discovery → config → the four business services → gateway. config-service resolves `../config-repo` from its working directory, so it must be started from `microservices/config-service`. The monolith's tests need `-Duser.timezone=Asia/Kolkata`.

## Rules that are easy to break

- **Authentication belongs to the gateway.** No `SecurityFilterChain` in a business service; controllers read the merchant only from `MerchantContext`.
- **No remote call inside `@Transactional`.** See `OrderPersistenceService` and `saga/PaymentAuthorizationRecorder`.
- **Events only through the outbox**; every `@Scheduled` job has a ShedLock `@SchedulerLock`.
- **Payment status changes only through `PaymentTransitionService`.**
- **Card numbers never leave vault-service**; only vault-service is configured with `vault.master-key`.
- **No cross-service foreign keys** — plain UUIDs.
- **New settings go in `config-repo`**, with a `-k8s.yaml` override and a ConfigMap / `secrets.env.example` entry when they differ in-cluster. A new Feign client needs a `url = "${<X>_SERVICE_URI:}"` override for Kubernetes.
- **A sealed type crossing Feign needs `@JsonTypeInfo`.**

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
