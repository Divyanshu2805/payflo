# Conventions

Rules the schema follows, and how to change it safely.

## Entities

- Every entity extends `common-lib`'s `BaseEntity` (UUID id, four audit columns) and uses `@GeneratedValue(strategy = GenerationType.UUID)`.
- Entities carry `@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder`. **Any field with a default value needs `@Builder.Default`** — without it Lombok's builder silently yields `null`, which on a non-null column fails only at insert time. The compiler warns; don't ignore it.
- A relation inside one service's database is a real `@ManyToOne` / `@OneToOne` with a foreign key. A reference into another service's database is a plain UUID — see [cross-service references](cross-service-references.md).

## Money is an embeddable, never a bare number

Amounts use `Money` (`amount_units` integer + `currency`). An entity with several amounts overrides the column names with `@AttributeOverrides`. Never add a `double` or `BigDecimal` amount column.

## Enums are strings

Every enum column is `@Enumerated(EnumType.STRING)` with an explicit `length`, and every enum lives in `common-lib` — see [enums](enums.md).

## Soft delete is a plain column

`customer` and `vault_card` have a nullable `deleted_at`. There is no `@SQLDelete` or `@Where`, so a query that must exclude deleted rows has to say so itself.

## Uniqueness is enforced by the database

Where a duplicate must be impossible, the table has a unique index rather than a check in code: `(merchant_id, receipt)` on `order_record`, `(merchant_id, idempotency_key)` on `payment`, `key_id` on `api_key`, `token` on `card_token`, `email` on `merchant` and `app_user`. A violation reaches the client as `409 DATA_INTEGRITY_VIOLATION`.

## Changing the schema

The schema is owned by **Flyway**. Each service has its own migrations in `src/main/resources/db/migration` (`V1__baseline.sql`, `V2__….sql`, …), and runs them on start-up against its own database. Hibernate only *checks* the result (`ddl-auto: validate`): an entity with no matching column fails the start, not a request later. So:

1. **Write a migration**, `V<next>__what_it_does.sql`, in the owning service. Never edit one that has been applied: Flyway refuses a changed migration. A new non-null column needs a default (or a backfill), or existing rows will fail it.
2. **Change the entity to match.** `contextLoads` runs every migration on an empty PostgreSQL and lets Hibernate validate the entities against it, so a migration and an entity that disagree fail the build.
3. **A new enum constant widens its check constraint in the same migration** (Hibernate used to leave the old list in place, which failed at runtime). `PaymentMethodTest` and the enum tests fail until you do.
4. **Never join another service's data.** A new reference to a merchant, customer or payment is a plain id.
5. **Update these pages and the ER diagram** in the same change.

A database that existed before Flyway was baselined at version 1 (`baseline-on-migrate`), so it keeps its data and only runs what came after. `V1__baseline.sql` is the schema as it stood, and builds it on an empty database.
