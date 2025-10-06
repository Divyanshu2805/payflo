# PayFlo

PayFlo is a payment gateway — the piece that sits behind a "Pay Now" button on a website, so a
business can take payments online, keep track of what was ordered and refunded, and get the money
into their bank account on a schedule. Currently in early development.

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
- Lombok
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
./mvnw.cmd test
```

## Project Status

Domain model (see the Entity Relationship Diagram in [docs/schema.md](docs/schema.md)) is
designed; implementation (entities, repositories, controllers, datasource config) is in progress.

## Documentation

Full project documentation — tech stack, domain model/ER diagram, APIs, and practices — is kept up to
date in [docs/](docs/README.md).
