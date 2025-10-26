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
- Lombok, MapStruct, Jakarta Bean Validation
- Maven

## Getting Started

Requires JDK 25 and a PostgreSQL instance.

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

**Phase 1 of 2 — monolith.** The system is being built as a single Spring Boot application first, with
the split into microservices planned as a deliberate second phase. Domain boundaries are cheap to move
inside one codebase and expensive to move once they're network calls, so they're being settled first —
the package layout and the no-cross-domain-foreign-key convention exist to keep that later split cheap.

Domain model (see the Entity Relationship Diagram in [docs/schema.md](docs/schema.md)) is
fully implemented as JPA entities. A first slice of the API now exists across two domains (merchant
signup and API key management, order creation, lookup, cancellation, and payment listing); the
rest of the repository/service/API layer is still to come.

## Documentation

Full project documentation — tech stack, domain model/ER diagram, APIs, and practices — is kept up to
date in [docs/](docs/README.md).
