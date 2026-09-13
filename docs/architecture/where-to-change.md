# Where Do I Change…?

A task-oriented index into the code. Paths are relative to each module's `src/main/java/com/project/payflo/<module>/` unless they start with a module or directory name.

## Payments

| I want to… | Look at |
|---|---|
| Add a payment method | A new `PaymentAdapter` in payment `gateway/adapter/` and `PaymentProcessor` in `processor/strategy/`, registered in `config/PaymentAdapterConfig` and `config/PaymentProcessorConfig`; the `PaymentMethod` enum in `common-lib` |
| Change a mock acquirer's test values | payment `processor/strategy/*PaymentProcessor.java`; for cards, vault `processor/CardPaymentProcessor.java` — and [the mock acquirer page](../api/mock-acquirer.md) |
| Change how a payment moves between statuses | payment `statemachine/PaymentStateMachine.java` (the table) and `PaymentTransitionService.java`; never set `Payment.status` directly |
| Change payment initiation | payment `saga/PaymentAuthorizationRecorder.java` (the two transactions and compensation) and `service/impl/PaymentServiceImpl.java` |
| Change how authorizations resolve | payment `simulator/BankCallbackSimulator.java`; `payment.simulator.*` in `config-repo/payment-service.yaml` |
| Change order creation | payment `service/impl/OrderServiceImpl.java` and `OrderPersistenceService.java` |

## Cards

| I want to… | Look at |
|---|---|
| Change tokenization or card validation | vault `service/impl/VaultServiceImpl.java`, `dto/request/TokenizeRequest.java`, `validation/` |
| Change how cards are encrypted | vault `config/VaultEncryptionConfig.java`; `common-lib` `config/AesEncryptionConfig.java` |

## Webhooks and settlement

| I want to… | Look at |
|---|---|
| Change who receives which event | merchant `service/impl/WebhookConfigServiceImpl.java` (subscriptions); operations `webhook/WebhookKafkaConsumer.java` (fan-out) |
| Change retry timing or the DLQ | operations `webhook/WebhookDeliverExecutor.java` (backoff, max attempts), `WebhookDeliveryScheduler.java`, `WebhookDlqRecorder.java` |
| Change settlement amounts or timing | operations `settlement/SettlementTransactionExecutor.java` (fee and GST rates), `SettlementEngine.java` (the cron) |
| Publish a new domain event | The owning service's `outbox/OutboxEventPublisher` inside the same transaction as the change; a topic in `config-repo` if the aggregate type is new |

## API, data and auth

| I want to… | Look at |
|---|---|
| Add a public endpoint | The owning service's `controller/` and DTOs, the [API reference](../api/README.md) — and, if the path prefix is new, a route in `config-repo/api-gateway-service.yaml` **and** `api-gateway-service-k8s.yaml` |
| Make an endpoint public (no credential) | `app.security.public-routes` in `config-repo/api-gateway-service.yaml` — think twice |
| Add a service-to-service call | An `Internal*Controller` endpoint in the owner, a Feign client method in the caller's `client/` with Resilience4j annotations and a `config-repo` instance, a `common-lib` DTO if shared, and the table in [service communication](service-communication.md#internal-api) |
| Add a column or table | The entity (Hibernate adds it on the next start), then the [data model](../schema/README.md) and the ER diagram |
| Change authentication | api-gateway `security/` (`GatewayAuthFilter`, `JwtAuthHandler`, `ApiKeyAuthHandler`); token issuing in merchant `security/JwtUtil.java` |
| Change rate limits | `app.rate-limit.*` in `config-repo/api-gateway-service.yaml`; algorithms in `common-lib` `ratelimit/` |
| Change the error shape or a status mapping | `common-lib` `exception/GlobalExceptionHandler.java` — every service picks it up |
| Add a shared bean to `common-lib` | The class, plus a registration in the matching `Shared*AutoConfiguration` — it is not component-scanned |

## Configuration and deployment

| I want to… | Look at |
|---|---|
| Add a setting | `microservices/config-repo/<service>.yaml` (and `<service>-k8s.yaml` if it differs in-cluster) |
| Add an environment variable on Kubernetes | `microservices/k8s/infra/configmap.yaml`, or `secrets.env.example` if secret, and the service's Deployment `env` |
| Add a service to Kubernetes | A manifest in `microservices/k8s/services/`, an entry in `kustomization.yaml`, Jib config in its `pom.xml` |
