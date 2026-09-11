# 0002. A database per service, no cross-service foreign keys

**Status:** Accepted

## Context

A service that reads another service's tables is coupled to its schema: neither can change or scale its storage independently, and a failure in one database takes down both. A shared database would also put card data within reach of every service.

## Decision

Each business service owns one PostgreSQL database — `payflo_merchant`, `payflo_payment`, `payflo_vault`, `payflo_operations` — and no service reads another's. A reference into another service's data is a plain UUID column with no JPA relation and no foreign key. Anything a service needs from another domain it asks for over that service's `/internal/**` API, or learns from an event.

Locally and on Kubernetes the four databases share one PostgreSQL server; on Kubernetes each has its own database user with privileges on its own database only.

## Consequences

- Services evolve their schemas independently, and vault-service's data is reachable only through vault-service.
- A dangling id is possible — nothing in the database prevents a `merchant_id` for a merchant that no longer exists — so code reading these columns must tolerate it.
- There are no cross-service transactions. Where two services must both change, the write order and [the outbox](0005-transactional-outbox-for-events.md) make the failure mode safe.
- One server is still one point of failure for all four databases; splitting servers later is a configuration change.
