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

There is no migration tool. Each service runs Hibernate with `ddl-auto: update`, which **adds** tables and columns from the entities on startup but never drops or renames anything and records no history. So:

1. **Change the entity**; the next start adds the column. Give a new non-null column a default, or existing rows will fail it.
2. **A rename or a removal needs a manual step** in every environment — Hibernate leaves the old column in place.
3. **Never join another service's data.** A new reference to a merchant, customer or payment is a plain id.
4. **Update these pages and the ER diagram** in the same change.

Moving to Flyway or Liquibase is tracked in [known gaps](../known-gaps/not-yet-built.md#platform).
