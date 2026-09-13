# Testing

## What exists

| Suite | Command | Covers | Needs |
|---|---|---|---|
| Each microservice | `./mvnw -pl <module> test` from `microservices/` | One `*ApplicationTests.contextLoads` per module — the Spring context starts | discovery-service, config-service and PostgreSQL/Redis/Kafka running, since each context imports its configuration from config-service |
| The monolith | `./mvnw test -Duser.timezone=Asia/Kolkata` from the root | `PayFloApplicationTests.contextLoads` | PostgreSQL; the time-zone flag ([the pitfall](gotchas/spring-and-jpa.md#postgresql-rejects-the-jvms-legacy-time-zone-name)) |

There are **no unit tests or integration tests** of business logic yet — that is the largest gap in the codebase, tracked in [known gaps](../gaps.md).

## What is verified by hand

Every change to a flow is verified end to end through the gateway, against the real infrastructure:

| Area | How to verify |
|---|---|
| Authentication | Signup → login → create an API key → call an API-key endpoint; a wrong secret is `401`; 201 requests in a minute on one key is a `429` |
| The payment path | Create an order with a `customer`, tokenize a card, pay by card and by UPI; each reaches `CAPTURED` within seconds and the order `PAID`. A [test failure value](../api/mock-acquirer.md) comes back `FAILED` |
| Compensation | Stop vault-service and pay by card: the payment ends `FAILED` with `PAYMENT_GATEWAY_ROUTER_UNREACHABLE` |
| Events | `ORDER_CREATED`, `PAYMENT_CREATED` and `PAYMENT_STATUS_CHANGED` appear on their topics in Control Center or Kafka UI |
| Webhooks | Register `…/webhook/success` as a target and watch `webhook_event` rows reach `DELIVERED`; register a URL that fails and watch `attempts` and `next_retry_at` advance |
| Settlement | Call `SettlementEngine.run()` rather than waiting for 23:00, then check `settlement` and the payments' `SETTLED` status. Not yet verified end to end — see [known gaps](../gaps.md) |
| Kubernetes | The steps in [running on kind](../deployment.md) — last done on a fresh cluster: all pods healthy, signup through a card payment reaching `CAPTURED`, events published |

A green `contextLoads` says nothing about any of these.

## Adding tests

- Business logic — the state machine table, fee and GST arithmetic, the saga's compensation, the mock processors — suits plain JUnit tests that construct the class directly and mock its collaborators, with no Spring context and no infrastructure.
- A controller change suits a `@WebMvcTest` slice with the service mocked; `MerchantContext` can be populated by sending `X-Merchant-Id`.
- A repository query with a lock or a unique index (`…ForUpdate`, `(merchant_id, idempotency_key)`) suits a `@DataJpaTest` against real PostgreSQL.
