# Practices

[← Back to docs index](README.md)

- Maven wrapper (`mvnw` / `mvnw.cmd`) used for reproducible builds.
- Lombok annotation processing wired into both compile and test-compile Maven executions; entities
  consistently use `@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder`. Any field with a
  default value carries `@Builder.Default` — without it Lombok silently drops the initializer and the
  builder produces `null`, which on a `nullable = false` column fails only at insert time.
- `pom.xml` overrides inherited `<name>`, `<description>`, `<license>`, `<developers>`, `<scm>` from the Spring Boot parent POM to avoid unwanted inheritance.
- Domain-oriented package structure (`common`, `merchant`, `payment`, `vault`, `operations`) instead of technical-layer packages, anticipating a future microservices split.
- Shared `BaseEntity` `@MappedSuperclass` for audit columns via Spring Data JPA auditing — the annotations are wired but auditing itself isn't yet enabled (`@EnableJpaAuditing` + an `AuditorAware` bean are still missing), so `created_by`/`updated_by` currently always come back null.
- Cross-service references (`merchant_id` on `ORDER_RECORD`/`PAYMENT`/`REFUND`) are stored as plain UUIDs with no JPA relationship — deliberate, since each domain is expected to eventually own its own database.
- Money represented via a shared `Money` embeddable value type (`long` smallest-unit amount + currency, add/subtract with currency-mismatch checks) rather than a raw amount column.
- Enums always persisted as strings (`@Enumerated(EnumType.STRING)`), never ordinals, so reordering an enum can't silently remap existing rows.
- Semantic, one-line commit messages (`feat:`, `fix:`, `docs:`, `chore:`, etc.).
- Service/controller layer (established by the merchant signup slice): request/response DTOs as
  `record`s in `dto/request`/`dto/response` with Jakarta Validation annotations; entity↔DTO mapping
  via a MapStruct interface in `mapper`; repositories as plain `JpaRepository` interfaces; service
  interface + impl (`service`/`service.impl`) constructor-injected and `@Transactional`; controllers
  under `/v1/...`.
