# Architecture Decisions

Short records of the decisions that shape PayFlo, each with the context that forced it and the trade-offs it accepts. They explain *why* the system looks the way it does; the rest of the architecture docs explain *what* it is.

| # | Decision | Status |
|---|---|---|
| [0001](0001-monolith-first-then-split.md) | Build a monolith first, then split it into services along the boundaries it found | Accepted |
| [0002](0002-database-per-service.md) | Give each service its own database, with no foreign keys across services | Accepted |
| [0003](0003-authenticate-once-at-the-gateway.md) | Authenticate every request once, at the gateway, and forward the identity | Accepted |
| [0004](0004-isolate-card-data-in-vault-service.md) | Confine card data to its own service and database | Accepted |
| [0005](0005-transactional-outbox-for-events.md) | Publish every event through a transactional outbox | Accepted |
| [0006](0006-payment-initiation-as-a-saga.md) | Run payment initiation as a saga, with no remote call inside a transaction | Accepted |
| [0007](0007-validated-payment-state-machine.md) | Move payment status only through a validated, logged state machine | Accepted |
| [0008](0008-configuration-in-the-repository.md) | Keep all configuration in the repository, served by a native config server | Accepted |

## Writing a new record

Add a file named `NNNN-short-title.md` with the next number, using the same sections as the existing records: **Status**, **Context**, **Decision**, **Consequences**. Once a record is accepted, don't rewrite it; if a decision changes, add a new record that supersedes it and update the old one's status.
