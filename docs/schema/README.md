# Data Model

The entities, tables, state machines and conventions behind PayFlo's four databases.

Data is split across **four PostgreSQL databases, one per business service**. Two consequences run through every page here:

- A service can only join its own tables. A reference into another service's data is a plain id column, never a foreign key.
- The schema is created and altered by Hibernate (`ddl-auto: update`) from the entities; there are no migration files yet.

## Contents

| Page | Covers |
|---|---|
| [Databases](databases.md) | Which service owns which tables, ids, money and audit columns |
| [merchant-service](merchant-service.md) | Merchants, dashboard users, API keys, customers, webhook configs |
| [payment-service](payment-service.md) | Orders, payments, the transition log, refunds, the outbox |
| [vault-service](vault-service.md) | Encrypted cards and the tokens that stand in for them |
| [operations-service](operations-service.md) | Webhook deliveries, the dead-letter queue, settlements, the outbox |
| [Cross-service references](cross-service-references.md) | Every column that points into another service's database |
| [Enums and state machines](enums.md) | Every status and type value, and the payment, settlement and delivery lifecycles |
| [Conventions](conventions.md) | Entities, money, enums, soft delete, and how to change the schema |

Keep these pages and the ER diagrams in step with each service's `entity/` package: update them in the same change as any entity edit. The frozen monolith's schema is not documented here — it differs in a few columns (for example a `refresh_token` table) and is kept only as a reference.
