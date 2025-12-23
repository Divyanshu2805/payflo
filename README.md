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

## Project Status

**Phase 1 (monolith) is feature-frozen as of 2025-12-16.** The system was built as a single Spring
Boot application first, on purpose — domain boundaries are cheap to move inside one codebase and
expensive to move once they're network calls, so the package layout and the
no-cross-domain-foreign-key convention exist to keep the split cheap. That split now begins; all
new work targets the microservices architecture in
[docs/architecture.md](docs/architecture.md#target).

Domain model (see the Entity Relationship Diagram in [docs/schema.md](docs/schema.md)) is
fully implemented as JPA entities. The monolith's working API spans five domains — merchant (signup,
login, API key management, webhook config), order and payment lifecycles (create/cancel/list,
initiate/capture, card tokenization), all backed by real JWT authentication
(`JwtAuthenticationFilter` resolves the caller's merchant on every request via `MerchantContext`).
Order and payment writes publish domain events through a Kafka-backed transactional outbox, which a
separate consumer turns into signed webhook deliveries with retries and a dead-letter queue.
Settlement, refunds, and analytics were never started, and a handful of known gaps (idempotency
enforcement, role/permission checks, rate-limiter edge cases, and more) remain open — see
[docs/status.md](docs/status.md#phase-1--phase-2-handoff) for the full carried-forward list
going into the split.

## Documentation

Full project documentation — tech stack, domain model/ER diagram, APIs, and practices — is kept up to
date in [docs/](docs/README.md).
