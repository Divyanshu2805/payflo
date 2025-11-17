# Practices

[← Back to docs index](README.md)

- Maven wrapper (`mvnw` / `mvnw.cmd`) used for reproducible builds.
- Lombok annotation processing wired into both compile and test-compile Maven executions; entities
  consistently use `@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder`. Any field with a
  default value carries `@Builder.Default` — without it Lombok silently drops the initializer and the
  builder produces `null`, which on a `nullable = false` column fails only at insert time.
- `pom.xml` overrides inherited `<name>`, `<description>`, `<license>`, `<developers>`, `<scm>` from the Spring Boot parent POM to avoid unwanted inheritance.
- Domain-oriented package structure (`common`, `merchant`, `payment`, `vault`, `operations`) instead of technical-layer packages, anticipating a future microservices split.
- Shared `BaseEntity` `@MappedSuperclass` for audit columns via Spring Data JPA auditing — `@EnableJpaAuditing` is on, so `created_at`/`updated_at` populate; `created_by`/`updated_by` still come back null pending an `AuditorAware` bean, which needs auth to exist first.
- Cross-service references (`merchant_id` on `ORDER_RECORD`/`PAYMENT`/`REFUND`) are stored as plain UUIDs with no JPA relationship — deliberate, since each domain is expected to eventually own its own database.
- Money represented via a shared `Money` embeddable value type (`long` smallest-unit amount + currency, add/subtract with currency-mismatch checks) rather than a raw amount column.
- Enums always persisted as strings (`@Enumerated(EnumType.STRING)`), never ordinals, so reordering an enum can't silently remap existing rows.
- Method-specific payment processing routed through a **strategy/adapter pattern**: a `PaymentAdapter`
  interface (`initiate(PaymentRequest)`/`capture(UUID)`, `payment/gateway`) with one implementation
  per `PaymentMethod` (`payment/gateway/adapter`
  — `CardPaymentAdapter`, `NetBankingAdapter`, `UpiPaymentAdapter`), selected at runtime by
  `PaymentGatewayRouter` from a `Map<PaymentMethod, PaymentAdapter>` bean assembled in
  `payment/config/PaymentAdapterConfig`. Keeps adding a payment method to a new adapter + one config
  line rather than a growing `switch`. Below that, `payment/processor` mirrors the same pattern one
  layer down: a `PaymentProcessor` interface (`charge(PaymentProcessorRequest):
  PaymentProcessorResponse`, the latter a sealed `Pending`/`Success`/`Failure`), one implementation
  per method in `payment/processor/strategy`, selected by `PaymentProcessorRouter` from a
  `Map<PaymentMethod, PaymentProcessor>` bean in `payment/config/PaymentProcessorConfig` —
  the acquirer-facing call an adapter delegates to. `NetBankingAdapter`/`UpiPaymentAdapter` call
  through to it; `CardPaymentAdapter` still doesn't call anything (see
  [Known gaps](gaps.md)).
- Card data encrypted with a **KEK/DEK pattern**: each `POST /v1/vault/tokenize` call generates a
  random per-card AES-256 data key (DEK), uses it to encrypt the PAN (`AesBytesEncryptor`, GCM),
  then wraps that DEK itself with a separate master key-encryption-key (KEK) —
  `VaultEncryptionConfig`'s `dekEncrypter` bean, sourced from `vault.encryption.master-key`. So
  compromising the database alone (encrypted PAN + wrapped DEK) isn't enough to recover a card; the
  KEK has to be compromised too, and it lives outside the database (`spring-security-crypto`, part
  of `spring-boot-starter-security`, provides `AesBytesEncryptor`/`KeyGenerators`). Pulling in
  `spring-boot-starter-security` for just the crypto classes has a side effect: Spring Boot's
  default autoconfiguration locks every endpoint behind HTTP Basic with a random per-restart
  password unless neutralized — `common/config/SecurityConfig` does that (a `SecurityFilterChain`
  permitting all requests), since no real auth exists yet.
- Semantic, one-line commit messages (`feat:`, `fix:`, `docs:`, `chore:`, etc.).
- Service/controller layer (established by the merchant signup slice): request/response DTOs as
  `record`s in `dto/request`/`dto/response` with Jakarta Validation annotations; entity↔DTO mapping
  via a MapStruct interface in `mapper`; repositories as plain `JpaRepository` interfaces; service
  interface + impl (`service`/`service.impl`) constructor-injected and `@Transactional`; controllers
  under `/v1/...`.
- Error handling: custom exceptions (`DuplicateResourceException`, `ResourceNotFoundException`,
  `ConflictException`) live in `common/exception`, extend `RuntimeException`, and carry an
  `errorCode`. A single `@RestControllerAdvice` (`GlobalExceptionHandler`, also in
  `common/exception`) maps them to the right HTTP status (`409`/`404`/`409`) and a shared
  `ErrorResponse` record (`errorCode`, `errorDescription`, `timestamp`, optional `fieldErrors`).
  Not yet wired up: a handler for `MethodArgumentNotValidException` (Bean Validation failures) —
  `ErrorResponse.FieldError` exists
  for this but nothing populates it yet.
