# PayFlo

**A payment gateway backend — orders, payments, a card vault, signed webhooks and nightly payouts — built as Spring Boot microservices.**

![Java 25](https://img.shields.io/badge/Java-25-orange)
![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F)
![Spring Cloud 2025.1](https://img.shields.io/badge/Spring%20Cloud-2025.1-6DB33F)
![Kafka](https://img.shields.io/badge/Kafka-outbox-231F20)
![Kubernetes](https://img.shields.io/badge/Kubernetes-kind-326CE5)

PayFlo is the piece that sits behind a "Pay now" button. A business signs up, gets API keys, creates orders and takes payments by card, UPI or net banking; card numbers are tokenized into an isolated vault; every change is announced to the business's own systems as a signed webhook; and each night the business is paid out, minus the platform fee and GST. The bank side is simulated end to end, so every flow runs locally with no external accounts.

## Features

- **Two ways in** — JWT for a merchant's staff, API keys (HTTP Basic) for a merchant's backend, both verified once at the gateway, with per-key rate limiting.
- **Orders and payments** — card, UPI and net banking through a strategy layer of adapters and processors, driven by a validated, logged payment state machine.
- **A saga, not a long transaction** — payment initiation never holds a database transaction across a network call, and compensates when the acquirer is unreachable.
- **Card vault** — PCI-scoped card data in its own service and database, with per-card envelope encryption; merchants only ever see a token.
- **Events through an outbox** — every domain change is published to Kafka from a transactional outbox, so no event is lost to a crash.
- **Webhooks** — HMAC-signed deliveries per subscribed endpoint, retried on a fixed schedule for 24 hours, then dead-lettered.
- **Nightly settlement** — per-merchant payouts with a fee and GST breakdown, linked back to every payment they cover.
- **Idempotent writes** — any write can be retried safely with `X-Idempotency-Key`.

## How it works

1. **Sign up and get credentials** — merchant-service creates the merchant and issues a JWT and API keys.
2. **Create an order** — payment-service records what's being paid for, resolving the customer from merchant-service first.
3. **Pay** — the payment moves to `AUTHORIZING`; a card is charged by vault-service using only its token.
4. **The bank answers** — a simulated bank authorizes and captures the payment within seconds; the order becomes `PAID`.
5. **The merchant is told** — the outbox publishes the change to Kafka, and operations-service delivers a signed webhook.
6. **The merchant is paid** — at 23:00 operations-service settles each merchant's captured payments to their bank account.

## Architecture

![PayFlo system architecture](docs/assets/diagrams/system-architecture.png)

A Spring Cloud Gateway authenticates every request and routes it to one of four business services — **merchant**, **payment**, **vault** and **operations** — each with its own PostgreSQL database. Services find each other through Eureka, pull their configuration from a config server that serves `config-repo/`, call each other over a private `/internal` API with circuit breakers and retries, and exchange events through Kafka. **Card numbers never leave vault-service.**

Read more: [architecture overview](docs/architecture/README.md) · [security model](docs/architecture/security-model.md) · [design decisions](docs/architecture/decisions/README.md)

## Tech stack

| Layer | Technologies |
|---|---|
| Backend | Java 25, Spring Boot 4.1, Spring Cloud 2025.1 (Gateway, Eureka, Config, OpenFeign), Resilience4j, Spring Data JPA, Lombok, MapStruct |
| Data and messaging | PostgreSQL (one database per service), Redis, Apache Kafka, ShedLock |
| Security | jjwt, bcrypt and AES-256-GCM via Spring Security Crypto |
| Infrastructure | Docker Compose, Jib, Kubernetes (kind), Kustomize |

Details and versions: [tech stack](docs/tech-stack.md).

## Getting started

**Prerequisites:** JDK 25 and Docker. No external accounts — the bank is simulated. See [prerequisites](docs/local-development/prerequisites.md).

```bash
git clone https://github.com/Divyanshu2805/payflo.git
cd payflo

# PostgreSQL, Redis, Kafka — then create the four service databases (see the setup guide)
docker compose -f services.docker-compose.yaml up -d

# Build every module
cd microservices && ./mvnw clean install -DskipTests

# One terminal per service, each from its module directory, in this order (mvnw.cmd on Windows)
cd discovery-service && ../mvnw spring-boot:run       # :8761
cd config-service && ../mvnw spring-boot:run          # :8888
cd merchant-service && ../mvnw spring-boot:run        # :8081  (then vault, payment, operations)
cd api-gateway-service && ../mvnw spring-boot:run     # :8080 — the only port clients call
```

Then sign up, log in, create an API key and make a payment through `http://localhost:8080`. The full guide, with the payment walkthrough, is in [local development](docs/local-development/README.md). To run everything on a local Kubernetes cluster instead, see [running on kind](docs/deployment/running-on-kind.md).

## Testing

Each module has a `contextLoads` test that needs discovery, config and the infrastructure running; the flows are verified end to end through the gateway by hand. Automated tests of the business logic are the largest open gap. See [testing](docs/practices/testing.md).

## Project structure

```
microservices/
  common-lib/             shared entities, enums, errors, MerchantContext, rate limiters, idempotency, DTOs
  discovery-service/      Eureka (:8761)
  config-service/         Spring Cloud Config over config-repo/ (:8888)
  config-repo/            every service's configuration, including the k8s profile
  api-gateway-service/    authentication, rate limiting, routing (:8080)
  merchant-service/       merchants, users, API keys, customers, webhook configs (:8081)
  payment-service/        orders, payments, state machine, saga, outbox, bank simulator (:8082)
  vault-service/          card tokenization and charging (:8083)
  operations-service/     webhook delivery and nightly settlement (:8084)
  k8s/                    Kubernetes manifests (Kustomize) and the kind cluster config
src/                      the frozen phase 1 monolith
services.docker-compose.yaml   local PostgreSQL, Redis, Kafka, Control Center
docs/                     documentation
```

## Deployment

The whole system runs on a local kind cluster: one namespace, the six applications built as Jib images, PostgreSQL, Redis and Kafka as StatefulSets, and only the gateway exposed. Secrets come from a gitignored file through a Kustomize `secretGenerator`, and each pod gets only the secrets it needs.

![Kubernetes deployment topology](docs/assets/diagrams/deployment-topology.png)

See [deployment](docs/deployment/README.md).

## Documentation

| Section | Contents |
|---|---|
| [Local development](docs/local-development/README.md) | Setup, configuration, troubleshooting, the monolith |
| [Architecture](docs/architecture/README.md) | Services, request flows, security model, decision records |
| [API reference](docs/api/README.md) | Every endpoint, the mock acquirer, idempotency, errors |
| [Data model](docs/schema/README.md) | Databases, entities, state machines, schema conventions |
| [Engineering practices](docs/practices/README.md) | Conventions, testing, guardrails, known pitfalls |
| [Known gaps](docs/known-gaps/README.md) | Trade-offs and what isn't built yet |
| [Deployment](docs/deployment/README.md) | Running on Kubernetes |

The full index is at [`docs/`](docs/README.md), and the design targets are in [requirements](docs/requirements.md).

## Contributing

[`CONTRIBUTING.md`](CONTRIBUTING.md) describes how to work on the codebase: the workflow, conventions and checks every change goes through. To report a security issue, follow [`SECURITY.md`](SECURITY.md) instead of opening a public issue.

## Project history

PayFlo was built as a single Spring Boot application first, so the boundaries between its domains could be found and moved cheaply, then split into the current services along those boundaries ([ADR 0001](docs/architecture/decisions/0001-monolith-first-then-split.md)). The monolith is frozen and kept at the repository root as the reference implementation — see [the monolith](docs/local-development/the-monolith.md). Settlement and Kubernetes deployment were built after the split.
