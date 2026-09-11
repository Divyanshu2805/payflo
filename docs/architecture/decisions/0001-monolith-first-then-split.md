# 0001. Build a monolith first, then split it

**Status:** Accepted

## Context

PayFlo's domains — merchants and credentials, orders and payments, card storage, and back-office operations — were clear on paper, but where exactly one ends and the next begins only shows up in code. Boundaries are cheap to move inside one codebase and expensive to move once they are network calls between separately deployed services. Service discovery, a gateway, per-service databases and messaging also cost real effort, and buy nothing until there is something worth separating.

## Decision

Build the whole system as a single Spring Boot application first (the repository root, `com.project.payflo`), while holding three conventions that keep a later split cheap:

- **Domain-oriented packages** — `common`, `merchant`, `payment`, `vault`, `operations` — rather than layer packages, so each domain is already a candidate service.
- **No cross-domain foreign keys** — a reference into another domain is a plain UUID.
- **Shared types isolated in `common`**, so what becomes a shared library is already identifiable.

Once the monolith covered onboarding, credentials, orders, payments, vaulting and webhooks, freeze it and extract one service per domain under `microservices/`, with `common` becoming `common-lib`.

## Consequences

- The split was mostly mechanical: each domain package became a service with little redesign, and the no-FK convention meant no schema had to be untangled.
- The monolith stays in the repository as a frozen reference. Nothing new is built there, and a few of its endpoints (refresh tokens, order reads and cancel) haven't been ported — see [known gaps](../../gaps.md).
- Settlement was never built in the monolith; it was built directly in operations-service.
- In-process calls became network calls, which brought the need for [the saga](0006-payment-initiation-as-a-saga.md), [the outbox](0005-transactional-outbox-for-events.md) and resilience wrappers.
