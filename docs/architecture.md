# Architecture

[← Back to docs index](README.md)

## Build strategy: monolith first

PayFlo is being built as a **single monolithic application first**, and will be split into microservices
only later, as a deliberate second phase. This is a decision, not an interim accident.

The reasoning: domain boundaries are cheap to move inside one codebase and expensive to move once
they're network calls between separately deployed services. Building the whole thing as a monolith lets
those boundaries be found and corrected while a mistake costs a refactor instead of a migration. The
distributed-systems machinery (service discovery, an API gateway, per-service databases, inter-service
messaging) also carries real operational cost, and it buys nothing until there's something worth scaling
independently.

So the split is being *prepared for* without being *paid for* yet — through three conventions held from
day one:

- **Domain-oriented packages** (`common`, `merchant`, `payment`, `vault`, `operations`) rather than
  layer-oriented ones (`controller`, `service`, `repository`), so each domain is a candidate service
  boundary already.
- **No cross-domain foreign keys** — `ORDER_RECORD`/`PAYMENT`/`REFUND` reference `merchant_id` as a plain
  UUID, because a real FK can't span two databases and would have to be torn out at split time anyway.
- **Shared types isolated in `common`**, so what would become a shared library is already identifiable.

Everything under **Target** below is the destination, not the current state, and not work in progress.

## Today

- A **single Spring Boot application** — one deployable, one database, no service-to-service calls.
- Base package `com.project.payflo`, organized by **domain**, not by technical layer — each domain owns
  its own `entity` (and eventually `service`/`repository`/`controller`) subpackages, anticipating the
  eventual microservices split below:
  - `common` — shared value types and enums used across domains (`BaseEntity`, `Money`, status/type enums).
  - `merchant` — merchant, API key, dashboard user, and customer entities.
  - `payment` — order, payment, refund, and payment-transition-log entities.
- Only the JPA entity layer exists so far — no `repository`, `service`, or `controller` layers, no APIs.
  The app compiles and can create its schema, but there's nothing to call yet.
- Standard Spring Boot layout otherwise (`src/main/java`, `src/main/resources`, `src/test/java`).

## Target

The **phase-two** architecture, to be built only after the monolith is complete. None of this exists
yet — no gateway, no service discovery, no message broker, no per-service databases — and none of it is
in progress. It's recorded here as the destination the conventions above are protecting the option to
reach.

```mermaid
flowchart LR
    subgraph clients["External Clients"]
        dashboard["Analytics Dashboard"]
        sdk["Checkout SDK"]
        backend["Merchant Backend"]
    end

    subgraph system["System Services"]
        gateway["API Gateway<br/>auth, rate limiting, routing"]
        discovery["discovery-service"]
        config["config-service"]
        commonlib["common-lib"]
    end

    subgraph business["Business Services"]
        merchantsvc["merchant-service<br/>authorization, keys, KYC"]
        paymentsvc["payment-service<br/>order, payment, refund"]
        opssvc["operations-service<br/>webhook, settlement, analytics"]
        vaultsvc["vault-service<br/>isolated PCI, tokenization"]
    end

    subgraph data["Data and Messaging"]
        redis[("Redis<br/>cache and counters")]
        kafka["Kafka events"]
        acquirer["Acquirer / Bank"]
        merchantdb[("merchant-db")]
        paymentdb[("payment-db")]
        opsdb[("operations-db")]
        vaultdb[("vault-db")]
    end

    subgraph obs["Observability"]
        zipkin["Zipkin tracing"]
        prom["Prometheus and Grafana"]
    end

    dashboard -->|JWT| gateway
    sdk --> gateway
    backend -->|API key| gateway

    gateway --> merchantsvc
    gateway --> paymentsvc
    gateway --> opssvc
    gateway --> vaultsvc

    merchantsvc --> merchantdb
    paymentsvc --> paymentdb
    opssvc --> opsdb
    vaultsvc --> vaultdb

    gateway --> redis
    merchantsvc --> redis

    paymentsvc -->|publish outbox events| kafka
    kafka -->|consume events| opssvc

    paymentsvc --> acquirer
    opssvc -->|signed webhooks| backend
```

Key points the diagram encodes:

- **`vault-service` is deliberately isolated** so PCI-regulated card data lives behind its own service
  and its own database, keeping the compliance scope as small as possible.
- **Database per service** — no shared database, which is why `ORDER_RECORD`/`PAYMENT`/`REFUND` reference
  `merchant_id` as a plain UUID instead of a foreign key.
- **Kafka decouples payment from operations** via the transactional outbox pattern: `payment-service`
  writes events to an outbox table in the same transaction as the payment, and `operations-service`
  consumes them to drive webhooks, settlement, and analytics.
- **Redis** backs both caching and the per-merchant rate-limit counters at the gateway.
