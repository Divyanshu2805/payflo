# Architecture

[← Back to docs index](README.md)

- Base package `com.project.payflo`, organized by **domain**, not by technical layer — each domain owns
  its own `entity` (and eventually `service`/`repository`/`controller`) subpackages, anticipating a future
  microservices split where each domain becomes its own service:
  - `common` — shared value types and enums used across domains (`BaseEntity`, `Money`, status/type enums).
  - `merchant` — merchant, API key, dashboard user, and customer entities.
  - `payment` — order, payment, refund, and payment-transition-log entities.
- Only the JPA entity layer exists so far — no `repository`, `service`, or `controller` layers, no APIs.
  The app compiles and can create its schema, but there's nothing to call yet.
- Standard Spring Boot layout otherwise (`src/main/java`, `src/main/resources`, `src/test/java`).
