# Module Map

What lives where, and the rules each layer follows.

## Repository layout

```
microservices/
  pom.xml                 aggregator only: lists every module, no shared parent
  mvnw, mvnw.cmd          one Maven wrapper for the whole build
  common-lib/             shared library code (below)
  test-support/           the PostgreSQL, Redis and Kafka containers every module's integration tests share
  discovery-service/      Eureka server
  config-service/         Spring Cloud Config server (native backend)
  config-repo/            every service's configuration: <service>.yaml, application.yaml, *-k8s.yaml
  api-gateway-service/    the public entry point: authentication, rate limits, routes
  merchant-service/       the merchant domain
  payment-service/        the payment domain
  vault-service/          the card vault
  operations-service/     webhooks and settlement
  k8s/, k8s-scaled/       Kubernetes manifests (Kustomize), the kind cluster config, and an overlay with three replicas
  observability/          Zipkin, Prometheus and Grafana, for Docker Compose and for the cluster
  load-test/              the JMeter plan and the scripts that run it
  chaos/                  the crash and outage tests
  dashboard/              a web dashboard over the public API: a static page and a loopback server that
                          forwards its /v1/** calls to the gateway. A client of the system, not part of it
  storefront/             a sample merchant's shop: shop, checkout and confirmation pages, and serve.py, the
                          merchant's backend, which calls the gateway with the API key and verifies the signed
                          webhook. A client of the system, not part of it
  demo/                   demo.py: start everything, seed a demo merchant, serve the dashboard and the shop
services.docker-compose.yaml   local PostgreSQL, Redis, Kafka, Control Center
docs/                     this documentation
```

Every module is its own Spring Boot application with its own `pom.xml`. Package roots are `com.project.payflo.<module>` with underscores (`payment_service`, `common_lib`).

## `common-lib`

A plain JAR (the Spring Boot repackage step is skipped) that every service depends on. Its beans are contributed through `META-INF/spring/…AutoConfiguration.imports` (`Shared*AutoConfiguration`), **not** by component scanning: a new class that must become a bean has to be registered there.

| Package | Owns |
|---|---|
| `entity` | `BaseEntity` (UUID id and the four JPA auditing columns) and the `Money` embeddable (`amountUnits` in the smallest currency unit + ISO currency). Nothing here is itself a table |
| `enums` | The whole domain vocabulary — see [enums](../schema/enums.md) |
| `exception` | The exception types, the single `GlobalExceptionHandler` and the shared `ErrorResponse` body, so every service returns identical errors |
| `context`, `web` | `MerchantContext` (request-scoped merchant id and API key id) and `MerchantContextFilter`, which rebuilds it from the gateway's identity headers |
| `audit` | `AuditorAwareImpl`, filling `created_by` / `updated_by` from `MerchantContext` |
| `config`, `util` | `AesEncryptionConfig` (the master-key encryptor, created only where a key is configured), `KafkaProperties` (topic per `EventAggregateType`), `RandomizerUtil`, `SignerUtil` (HMAC-SHA256) |
| `ratelimit` | `FixedWindowRateLimiter` (the gateway's limits: one atomic Redis increment per request, failing open) and `VelocityCounters` (the windows behind the card-testing rules) |
| `idempotency` | `IdempotencyFilter` and `RedisIdempotencyStore`, registered for every business service by `SharedWebFilterAutoConfiguration`. Binds a key to the request (fingerprint), never stores a once-only secret, never hashes a card or password body — see [idempotency](../api/idempotency-and-rate-limits.md) |
| `logging` | `@MaskedCard`, `CardMasker` (Luhn-checked masking of card numbers in text, and `describe` for a `toString` without card fields) and the two Logback converters that apply it to every message and stack trace. Wired by `common-lib`'s `logback-spring.xml`, which every service picks up |
| `outbox` | The transactional outbox: the `OutboxEvent` entity and its repository, `OutboxEventPublisher` (writes a row in the caller's transaction), `OutboxPoller` (publishes to Kafka in acknowledged batches), `OutboxResultHandler` and `OutboxMaintenance` (requeue and purge). Not auto-configured: payment-service and operations-service opt in with `@Import(OutboxConfiguration.class)`, and each has its own `outbox_event` table |
| `cache` | `ApiKeyCache` / `RedisApiKeyCache` — the gateway's cache of API keys |
| `dto` | The contracts services exchange over Feign: `FindOrCreateCustomerRequest`, `VaultChargeRequest`, `PaymentProcessorRequest` / `PaymentProcessorResponse`, `PaymentSettlementView`, `SettlementBankDetails`, `WebhookTarget` |

## Inside a business service

The four business services share one layering. Package names are relative to `com.project.payflo.<module>`.

| Package | Owns | Must never |
|---|---|---|
| `entity` | JPA mappings extending `BaseEntity` — see the [data model](../schema/README.md) | Hold a JPA relation to another service's entity |
| `repository` | Spring Data JPA interfaces with derived queries; `…ForUpdate` finders take a pessimistic lock | Decide anything |
| `mapper` | Entity ↔ DTO conversion with MapStruct | Leave an "Unmapped target property" warning unfixed |
| `service` / `service.impl` | Business logic — one interface and one implementation per concern | Call another service inside a `@Transactional` method |
| `controller` | `/v1/...` endpoints and `Internal*Controller`s under `/internal/**` | Read the merchant from anywhere but `MerchantContext` |
| `dto` | Request and response records, `dto/request` and `dto/response` | Carry validation annotations on a response record |
| `client` | Feign clients for other services' internal APIs, each wrapped in Resilience4j | Be called from inside a database transaction |
| `config` | Bean wiring — adapter and processor maps, ShedLock, the scheduler's thread pool | Define a `SecurityFilterChain` — authentication is the gateway's job |

### What each service adds

- **merchant-service** — `security/JwtUtil` (issues tokens), `security/SessionService` (refresh tokens and revocation, in Redis), `security/CallerPolicy` (role checks for dashboard-only actions), `security/LoginAttemptTracker`, `security/BankAccountCipher`, `service/MerchantProfileService` (profile, payout account, simulated KYC), `service/UserManagementService`, `service/AuditLogService` (the append-only audit log, written in the caller's transaction), `service/AdminMerchantService` and `controller/AdminController` (the operator's suspend and reactivate), `api/MerchantLookupService` behind the internal controllers.
- **payment-service** — `statemachine` (`PaymentStateMachine`, `PaymentTransitionService`), `saga/PaymentAuthorizationRecorder`, `gateway` (`PaymentAdapter` per method, chosen by `PaymentGatewayRouter`), `processor` (`PaymentProcessor` per method, chosen by `PaymentProcessorRouter`), `simulator/BankCallbackSimulator` and `RefundResolver`, `service/RefundService` and `RefundProcessingService`, `service/PaymentQueryService`, `timeout/PaymentTimeoutSweeper`, `api/PaymentLookupService` for settlement.
- **vault-service** — `config/VaultEncryptionConfig` (per-card data keys), `processor/CardPaymentProcessor` (the mock card acquirer), `validation/@ExpiryYear`.
- **operations-service** — `webhook` (consumer, retry queue, scheduler, executor, delivery recorder, DLQ recorder), `settlement` (engine, the transaction executor that drives the saga, `SettlementRecorder`, `SettlementRecoveryJob`, integration gateway, bank simulators). It is mostly driven by Kafka and schedules, with read endpoints for settlements and webhook deliveries (`controller`, `service`).
- **api-gateway-service** — `security` (`GatewayAuthFilter`, `JwtAuthHandler`, `ApiKeyAuthHandler`, `HeaderAugmentingRequestWrapper`, `PublicRouteMatcher`, `AuthFailureTracker`, `MerchantStatusChecker`, `AdminAuthHandler` for the operator's key) and `client/ApiKeyLookupClient`. Routes are configuration, not code.
