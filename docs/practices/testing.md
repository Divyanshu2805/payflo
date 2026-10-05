# Testing

## What exists

| Suite | Command | Covers | Needs |
|---|---|---|---|
| Each microservice | `./mvnw -pl <module> test` from `microservices/` | One `*ApplicationTests.contextLoads` per module — the Spring context starts | discovery-service, config-service and PostgreSQL/Redis/Kafka running, since each context imports its configuration from config-service |
| The monolith | `./mvnw test -Duser.timezone=Asia/Kolkata` from the root | `PayFloApplicationTests.contextLoads` | PostgreSQL; the time-zone flag ([the pitfall](gotchas/spring-and-jpa.md#postgresql-rejects-the-jvms-legacy-time-zone-name)) |

Plain JUnit unit tests (no Spring context, no infrastructure — run them with `-Dtest=<Class>` to skip `contextLoads`) cover the first money- and security-critical pieces:

| Test | Covers |
|---|---|
| `common-lib` `IdempotencyFilterTest` | Replay, key scoped by merchant + method + path, nothing cached without a merchant, a failed request releases its key |
| `common-lib` `WebhookUrlValidatorTest` | Which webhook URLs are refused (private, loopback, link-local, metadata, http, credentials) in strict and development mode |
| `common-lib` `InternalApiAuthFilterTest`, `SecretConfigurationCheckerTest` | The `/internal/**` token (missing, wrong, blank) and the start-up refusal of default or weak secrets |
| `api-gateway-service` `GatewayAuthFilterTest`, `HeaderAugmentingRequestWrapperTest`, `AuthFailureTrackerTest` | Identity headers stripped on every route, suspended merchants refused, failed-auth blocking and counting, signup/login limits, fail-open when Redis is down |
| `merchant-service` `AuthServiceImplTest`, `LoginAttemptTrackerTest`, `ApiKeyServiceImplTest` | Login lockout, uniform timing for unknown emails, suspended merchants; key rotation grace periods and cache eviction after commit |
| `payment-service` `OrderAmountTest` | Order amount and currency validation |
| `payment-service` `PaymentAuthorizationRecorderTest` | One live payment per order; a failed attempt doesn't block a retry; an expired order isn't payable |
| `payment-service` `PaymentServiceImplTest` | `methodDetails` validation, an ambiguous timeout left `AUTHORIZING`, no exception text returned to the merchant |
| `payment-service` `PaymentTimeoutServiceTest`, `OutboxMaintenanceTest`, `PaymentTest` | Timeouts and order expiry re-checked under a lock; outbox requeue and purge; error fields cut to column width |
| `vault-service` `VaultServiceImplTest` | A card token is only chargeable by the merchant that created it |
| `operations-service` `SettlementTransactionExecutorTest` | Fee/GST arithmetic, int-overflow splitting, per-currency settlements, in-flight payments excluded, merchants without a bank account skipped |
| `operations-service` `WebhookDeliveryRecorderTest`, `WebhookKafkaConsumerTest`, `WebhookDeliverySchedulerTest` | Delivery claim and lease, retry/backoff/dead-lettering; transient vs. permanent consumer failures (nack vs. DLQ); reconciling `FAILED` as well as `PENDING` |

There are still **no integration tests** and no tests of the rest of the business logic (the state machine, the saga's compensation, the mock processors) — the largest gap in the codebase, tracked in [known gaps](../known-gaps/not-yet-built.md#platform).

## What is verified by hand

Every change to a flow is verified end to end through the gateway, against the real infrastructure:

| Area | How to verify |
|---|---|
| Authentication | Signup → login → create an API key → call an API-key endpoint; a wrong secret is `401`; 201 requests in a minute on one key is a `429` |
| The payment path | Create an order with a `customer`, tokenize a card, pay by card and by UPI; each reaches `CAPTURED` within seconds and the order `PAID`. A [test failure value](../api/mock-acquirer.md) comes back `FAILED` |
| Idempotency | `python idempotency_replay_test.py` in `microservices/load-test/` — every key must resolve to one order or payment ([replay test](../load-testing/running.md#6-replay-test-for-idempotency)) |
| Compensation | Stop vault-service and pay by card: the payment ends `FAILED` with `PAYMENT_GATEWAY_ROUTER_UNREACHABLE` |
| Events | `ORDER_CREATED`, `PAYMENT_CREATED` and `PAYMENT_STATUS_CHANGED` appear on their topics in Control Center or Kafka UI |
| Webhooks | Register `…/webhook/success` as a target and watch `webhook_event` rows reach `DELIVERED`; register a URL that fails and watch `attempts` and `next_retry_at` advance |
| Settlement | Call `SettlementEngine.run()` rather than waiting for 23:00, then check `settlement` and the payments' `SETTLED` status. The merchant must be `ACTIVE` (`update merchant set status = 'ACTIVE'` in `payflo_merchant` — there's no KYC flow). Verified end to end: a run settles every captured payment, records gross/fee/GST/net, and the payout simulator moves it to `PROCESSED`. |
| Kubernetes | The steps in [running on kind](../deployment/running-on-kind.md) — last done on a fresh cluster: all pods healthy, signup through a card payment reaching `CAPTURED`, events published |

A green `contextLoads` says nothing about any of these.

## Load testing

The JMeter plan in `microservices/load-test/` drives orders and payments through the gateway and grades the run against the throughput, p99 and availability targets. See [load testing](../load-testing/README.md).

## Adding tests

- Business logic — the state machine table, fee and GST arithmetic, the saga's compensation, the mock processors — suits plain JUnit tests that construct the class directly and mock its collaborators, with no Spring context and no infrastructure.
- A controller change suits a `@WebMvcTest` slice with the service mocked; `MerchantContext` can be populated by sending `X-Merchant-Id`.
- A repository query with a lock or a unique index (`…ForUpdate`, `(merchant_id, idempotency_key)`) suits a `@DataJpaTest` against real PostgreSQL.
