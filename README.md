# PayFlo

PayFlo is a payment gateway — the piece that sits behind a "Pay Now" button on a website, so a
business can take payments online, keep track of what was ordered and refunded, and get the money
into their bank account on a schedule. Currently under active development.

## Features (planned)

- Merchant onboarding, KYC, API key management
- Order, payment, and refund lifecycles with idempotent writes and state machines
- Card tokenization/vaulting with AES-256 encryption
- Signed webhook delivery with retries, DLQ, and replay
- Nightly batch settlement with fee/GST breakdown and audit trail
- Multi-tenant auth (API key + JWT) and per-merchant rate limiting
- Real-time and historical analytics dashboards

Designed for 10k TPS, p99 < 1s, 99.99% availability, and PCI DSS compliance — see the full
functional and non-functional requirements in [docs/requirements.md](docs/requirements.md).

## Tech Stack

- Java 25
- Spring Boot 4.1.0 (Spring MVC, Spring Data JPA)
- PostgreSQL
- Redis (rate limiting, idempotency keys, API key cache, webhook retry queue)
- Apache Kafka (transactional outbox for domain events, consumed for webhook delivery)
- Spring Cloud (phase 2): Eureka, Config Server, Gateway, OpenFeign, Resilience4j
- ShedLock (single-instance scheduled jobs)
- Lombok, MapStruct, Jakarta Bean Validation
- Maven

## Getting Started

Requires JDK 25, a PostgreSQL instance, a Redis instance, and a Kafka broker — `services.docker-compose.yaml`
brings up all three (plus a Kafka control-center UI) for local development.

```bash
./mvnw.cmd clean compile
```

```bash
./mvnw.cmd spring-boot:run
```

```bash
./mvnw.cmd test -Duser.timezone=Asia/Kolkata
```

The microservices build lives in `microservices/` — see
[docs/getting-started.md](docs/local-development/README.md) for the startup order.

## Project Status

PayFlo was built in two phases. **Phase 1** was a single application, built first on purpose so the
boundaries between its parts could be adjusted cheaply before they became network calls. It's now
frozen and kept at the repo root for reference.

**Phase 2** splits it into separate services under [`microservices/`](microservices), each with its
own database:

- **api-gateway** — the single front door: checks who's calling (dashboard login or API key),
  enforces rate limits, and routes the request
- **merchant-service** — merchant accounts, logins, API keys, customers, webhook settings
- **payment-service** — orders and payments, from "Pay" to money captured
- **vault-service** — the only place card numbers are ever stored or read, kept separate to limit
  PCI scope
- **operations-service** — tells merchants what happened (signed webhooks, with retries) and pays
  them out nightly (settlement, with fees and GST)
- plus **discovery**, **config**, and a shared **common-lib**

A payment now flows end to end through the gateway — order, card or UPI payment, simulated bank
approval, capture — and settlement, which the monolith never had, is built. Refunds and analytics
aren't started. The whole system can also be run on Kubernetes (a local kind cluster — see
[docs/deployment.md](docs/deployment.md)); monitoring and load testing are still to come. See
[docs/status.md](docs/known-gaps/README.md) for the detailed status and [docs/gaps.md](docs/known-gaps/README.md) for known
gaps.

## Documentation

Full project documentation — tech stack, domain model/ER diagram, APIs, and practices — is kept up to
date in [docs/](docs/README.md).
