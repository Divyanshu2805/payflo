# Practices

[← Back to docs index](README.md)

- Maven wrapper (`mvnw` / `mvnw.cmd`) used for reproducible builds.
- Lombok annotation processing wired into both compile and test-compile Maven executions; entities
  consistently use `@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder`. Any field with a
  default value carries `@Builder.Default` — without it Lombok silently drops the initializer and the
  builder produces `null`, which on a `nullable = false` column fails only at insert time.
- `pom.xml` overrides inherited `<name>`, `<description>`, `<license>`, `<developers>`, `<scm>` from the Spring Boot parent POM to avoid unwanted inheritance.
- Domain-oriented package structure (`common`, `merchant`, `payment`, `vault`, `operations`) instead of technical-layer packages, anticipating a future microservices split.
- Shared `BaseEntity` `@MappedSuperclass` for audit columns via Spring Data JPA auditing — `@EnableJpaAuditing(auditorAwareRef = "auditorAwareImpl")` is on, so all four columns populate now: `created_at`/`updated_at` automatically, and `created_by`/`updated_by` via `audit/AuditorAwareImpl` reading `merchant/security/MerchantContext` (API key's `keyId`, else `"merchant_id: <uuid>"` from a JWT, else `"SYSTEM"` outside any request — guarded with a `try/catch` since `MerchantContext` is `@RequestScope`).
- Cross-service references (`merchant_id` on `ORDER_RECORD`/`PAYMENT`/`REFUND`) are stored as plain UUIDs with no JPA relationship — deliberate, since each domain is expected to eventually own its own database.
- Money represented via a shared `Money` embeddable value type (`long` smallest-unit amount + currency, add/subtract with currency-mismatch checks) rather than a raw amount column.
- Enums always persisted as strings (`@Enumerated(EnumType.STRING)`), never ordinals, so reordering an enum can't silently remap existing rows.
- Method-specific payment processing routed through a **strategy/adapter pattern**: a `PaymentAdapter`
  interface (`initiate(PaymentRequest)`/`capture(UUID)`, `payment/gateway`) with one implementation
  per `PaymentMethod` (`payment/gateway/adapter`
  — `CardPaymentAdapter`, `NetBankingAdapter`, `UpiPaymentAdapter`, `WalletPaymentAdapter`),
  selected at runtime by `PaymentGatewayRouter` from a `Map<PaymentMethod, PaymentAdapter>` bean
  assembled in `payment/config/PaymentAdapterConfig`. Keeps adding a payment method to a new
  adapter + one config line rather than a growing `switch`. Below that, `payment/processor` mirrors
  the same pattern one layer down: a `PaymentProcessor` interface (`charge(PaymentProcessorRequest):
  PaymentProcessorResponse`, the latter a sealed `Pending`/`Success`/`Failure`), one implementation
  per method in `payment/processor/strategy`, selected by `PaymentProcessorRouter` from a
  `Map<PaymentMethod, PaymentProcessor>` bean in `payment/config/PaymentProcessorConfig` — the
  acquirer-facing call an adapter delegates to. `NetBankingAdapter`/`UpiPaymentAdapter`/
  `WalletPaymentAdapter` call through to it directly; `CardPaymentAdapter` calls through too, via
  `vault/service/VaultService.charge` (decrypts the vaulted card first — see the KEK/DEK bullet
  below).
- Card data encrypted with a **KEK/DEK pattern**: each `POST /v1/vault/tokenize` call generates a
  random per-card AES-256 data key (DEK), uses it to encrypt the PAN (`AesBytesEncryptor`, GCM),
  then wraps that DEK itself with a separate master key-encryption-key (KEK) —
  `VaultEncryptionConfig`'s `dekEncrypter` bean, sourced from `vault.encryption.master-key`. So
  compromising the database alone (encrypted PAN + wrapped DEK) isn't enough to recover a card; the
  KEK has to be compromised too, and it lives outside the database (`spring-security-crypto`, part
  of `spring-boot-starter-security`, provides `AesBytesEncryptor`/`KeyGenerators`). Pulling in
  `spring-boot-starter-security` for just the crypto classes has a side effect: Spring Boot's
  default autoconfiguration locks every endpoint behind HTTP Basic with a random per-restart
  password unless a `SecurityFilterChain` bean is defined — `merchant/security/WebSecurityConfig`
  now defines two, both enforced (see the JWT and API-key bullets below). Only one filter chain may
  match "any request" with no `securityMatcher`; both of these are scoped, which is why the older
  unscoped placeholder `common/config/SecurityConfig` was removed rather than kept alongside them.
- **JWT auth** — `merchant/security/JwtUtil` (`io.jsonwebtoken`/`jjwt`, HMAC-signed via
  `jwt.secret-key` in `application.yaml`) generates and verifies access tokens carrying
  `merchant_id`/`role` claims. `POST /v1/auth/login` calls it and returns a real token for a
  correct email/password. `WebSecurityConfig.jwtChain` requires it for `/v1/merchants/**`/
  `/v1/admin/**`/`/actuator/**` (permitting only signup/login/webhook) — `merchant/security/
  JwtAuthenticationFilter` reads the `Authorization: Bearer` header, verifies it, and resolves
  `MerchantContext` from the `merchant_id` claim.
- **API-key auth** — the server-to-server counterpart, for a merchant's own backend calling in
  directly rather than a human via the dashboard. `merchant/security/ApiKeyAuthenticationFilter`,
  on a second chain (`WebSecurityConfig.apiKeyChain`) covering `/v1/orders/**`/`/v1/payments/**`/
  `/v1/vault/**`, decodes an `Authorization: Basic base64(keyId:secret)` header, looks up the
  `ApiKey` by `keyId`, and bcrypt-compares the secret against `keySecretHash` — or, during the 24h
  post-rotation window, against `previousKeySecretHash` too. Resolves the same `MerchantContext` the
  JWT filter does, just from a different source, so `OrderController`/`PaymentController`/
  `VaultController` don't need to know which mechanism authenticated the request.
- `payment/simulator` mocks the async, bank-side half of a payment (the part `PaymentProcessor`'s
  synchronous mock-acquirer logic doesn't cover) — a config-driven **`BankCallbackSimulator`**
  (currently disabled again, see [Known gaps](gaps.md)) polls for
  `AUTHORIZING` payments and resolves each one after a per-method simulated delay and success rate
  (`SimulatorConfig`, bound from `payment.simulator.*` in `application.yaml`), with
  a global `ChaosMode` (`NORMAL`/`SLOW`/`SUCCESS`/`FAILURE`/`TIMEOUT`) to force deterministic
  outcomes for testing rather than relying on the random success rate.
- Semantic, one-line commit messages (`feat:`, `fix:`, `docs:`, `chore:`, etc.).
- Service/controller layer (established by the merchant signup slice): request/response DTOs as
  `record`s in `dto/request`/`dto/response` with Jakarta Validation annotations; entity↔DTO mapping
  via a MapStruct interface in `mapper`; repositories as plain `JpaRepository` interfaces; service
  interface + impl (`service`/`service.impl`) constructor-injected and `@Transactional`; controllers
  under `/v1/...`.
- Error handling: custom exceptions (`DuplicateResourceException`, `ResourceNotFoundException`,
  `ConflictException`, `InvalidStateTransitionException`, `UnsupportedPaymentMethodException`) live
  in `common/exception`, extend `RuntimeException`, and carry an `errorCode`. A single
  `@RestControllerAdvice` (`GlobalExceptionHandler`, also in `common/exception`) maps them to the
  right HTTP status (`409`/`404`/`409`/`409`/`400` respectively) and a shared `ErrorResponse`
  record (`errorCode`, `errorDescription`, `timestamp`, optional `fieldErrors`). Also handles Bean
  Validation failures (`MethodArgumentNotValidException` → `400`, `VALIDATION_FAILED`, with
  per-field `fieldErrors` populated from the binding result), Spring Security's
  `AuthenticationException` (→ `401`, `INVALID_CREDENTIALS` — covers both a wrong login password
  and an unknown login email, deliberately indistinguishable), and `ApiKeyAuthenticationFilter`'s
  `org.apache.coyote.BadRequestException` (→ `401`, `INVALID_API_KEY`) for a malformed, unknown, or
  wrong-secret API key.
