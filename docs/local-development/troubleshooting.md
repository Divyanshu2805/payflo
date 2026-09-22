# Troubleshooting

Common problems and their fixes. Deeper explanations of the silent-failure traps are in [known pitfalls](../practices/gotchas/README.md).

## Start-up

| Symptom | Cause | Fix |
|---|---|---|
| A service exits with "Could not resolve placeholder" or fails to import `configserver:` | config-service isn't up yet, or can't find `config-repo` | Start config-service before the business services, **from its module directory** so `file:../config-repo` resolves; check `curl localhost:8888/<service>/default` |
| config-service starts but serves empty property sources | Started from the wrong working directory | Start it from `microservices/config-service`, or set `CONFIG_REPO_PATH` to an absolute `file:` path |
| `FATAL: invalid value for parameter "TimeZone": "Asia/Calcutta"` | The JVM sends the legacy zone name, which PostgreSQL rejects | Pass `-Duser.timezone=Asia/Kolkata` (see [the pitfall](../practices/gotchas/spring-and-jpa.md#postgresql-rejects-the-jvms-legacy-time-zone-name)) |
| `database "payflo_merchant" does not exist` | The per-service databases were never created | Run the `CREATE DATABASE` command from [setup](setup.md#2-create-the-four-databases) |
| The gateway fails with `No qualifying bean of type 'RateLimiter'` | `app.rate-limit.method` is unset | Set it in `config-repo/api-gateway-service.yaml` (`fixed` by default) |
| `NoClassDefFoundError` for a class you just added to `common-lib` | The service resolved an old `common-lib` jar from `~/.m2` | `./mvnw install` in `microservices/` again, or build as a reactor with `-pl common-lib,<module>` |
| "Port 8080 was already in use" | Something else on the machine uses 8080 | Start the gateway with `--server.port=<port>` |
| The gateway's `/actuator/health` answers `401` | Actuator isn't on the gateway's public port | Use the management port: `localhost:9081/actuator/health` |

## Requests

| Symptom | Cause | Fix |
|---|---|---|
| `401` on every call | No or bad `Authorization` header — the gateway only accepts `Bearer <jwt>` or `Basic keyId:secret` | Log in again (the JWT lasts 100 minutes) or check the key — a rotated key's old secret stops working 24 hours after rotation |
| `429` with `Retry-After` | Over 200 requests per minute on one API key | Wait, or raise `requests-per-minute` locally |
| A `/v1/...` call directly on `:8081`–`:8084` behaves as if there's no merchant | Direct calls skip the gateway, so no `X-Merchant-Id` is set | Always go through `:8080` |
| A card payment comes back `FAILED` with `PAYMENT_GATEWAY_ROUTER_UNREACHABLE` | vault-service is down, its circuit breaker is open, or `methodDetails.token` is missing or unknown | The saga compensated and published `PAYMENT_AUTHORIZATION_COMPENSATED`. Check vault-service and the token, then retry with a new payment |
| A payment stays `AUTHORIZING` | payment-service's scheduler isn't running, `chaos-mode` is `TIMEOUT`, or a backlog is ahead of it — the simulator works through the oldest 500 per run, so after a load test new payments wait their turn | Check payment-service's log for `BankCallbackSimulator`; check `payment.simulator.chaos-mode` |
| No webhook arrives | No `MerchantWebhookConfig` subscribed to that event, the target is failing, or the outbox is behind | `GET /v1/merchants/webhooks`; look at `webhook_event.last_response_code` in `payflo_operations`; check `payflo_outbox_pending` on payment-service's `/actuator/prometheus` |
| `503 DEPENDENCY_UNAVAILABLE` | A circuit breaker to merchant- or vault-service is open | Check that service; the breaker half-opens after 10 s and closes once calls succeed |
| Settlement fails with `null value in column "bank_reference"` (or `"refund_amount_units"`) violates not-null constraint | The database was created before these columns became nullable / populated; `ddl-auto: update` never relaxes an existing constraint | `ALTER TABLE settlement ALTER COLUMN bank_reference DROP NOT NULL;` in `payflo_operations`, or [reset the database](resetting-data.md) |

## Kafka

| Symptom | Cause | Fix |
|---|---|---|
| Services can't reach Kafka on `localhost:9092` | The host listener is `29092`; `9092` is the in-network listener | Use `KAFKA_BROKERS=localhost:29092` (the default) |
| Outbox rows stuck in `PENDING` | Kafka isn't reachable, or no instance holds the ShedLock | Check `outbox_event.last_error`; after 3 failed attempts a row is marked `FAILED` and never retried |
