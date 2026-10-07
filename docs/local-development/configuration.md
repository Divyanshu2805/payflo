# Configuration

## Where settings live

Every service's own `application.yaml` holds only its name and one line:

```yaml
spring.config.import: configserver:${CONFIG_SERVER_URL:http://localhost:8888}
```

Everything else — ports, datasources, Kafka, Redis, routes, resilience settings, simulator knobs, secrets with development defaults — lives in [`microservices/config-repo/`](../../microservices/config-repo), served by config-service's native backend:

| File | Applies to |
|---|---|
| `application.yaml` | Every service: the Eureka URL, Redis, the JWT secret, virtual threads, Actuator exposure, tracing and metrics |
| `<service>.yaml` | One service: its port, datasource, Kafka topics, Feign/Resilience4j instances and domain settings |
| `application-k8s.yaml`, `<service>-k8s.yaml` | The same, when a service runs with the `k8s` profile on Kubernetes — see [deployment configuration](../deployment/configuration.md) |

Add a new property to `config-repo`, never to a module's `application.yaml`.

## Environment variables

Each value can be overridden with the environment variable named in its placeholder. The defaults work against `services.docker-compose.yaml` as-is.

| Variable | Default | Used by |
|---|---|---|
| `CONFIG_SERVER_URL` | `http://localhost:8888` | every service |
| `CONFIG_REPO_PATH` | `file:../config-repo` | config-service — relative to its working directory, so start it from its module directory |
| `EUREKA_URL` | `http://localhost:8761/eureka` | every service |
| `MERCHANT_DB_URL`, `PAYMENT_DB_URL`, `VAULT_DB_URL`, `OPERATIONS_DB_URL` | `jdbc:postgresql://localhost:5432/payflo_<service>` | the business services |
| `DB_USER`, `DB_PASS` | `user` / `password` | the business services |
| `MERCHANT_DB_POOL_SIZE`, `PAYMENT_DB_POOL_SIZE`, `VAULT_DB_POOL_SIZE`, `OPERATIONS_DB_POOL_SIZE` | `20`, `40`, `10`, `16` | each business service's database connection pool — keep the sum under PostgreSQL's `max_connections` |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | `localhost`, `6380`, empty | the gateway and every business service (idempotency, caches, the retry queue, ShedLock) |
| `KAFKA_BROKERS` | `localhost:29092` | payment, operations |
| `JWT_SECRET` | a development-only value | merchant-service (signs) and the gateway (verifies) — must be identical |
| `VAULT_MASTER_KEY` | a development-only value | vault-service only |
| `WEBHOOK_SECRET_KEY` | a development-only value | merchant-service only — encrypts webhook signing secrets |
| `INTERNAL_API_TOKEN` | a development-only value | every service — required on `/internal/**` (header `X-Internal-Token`), sent by every Feign client; must be identical everywhere and must not be empty |
| `ADMIN_API_KEY` | a development-only value | the gateway only — the platform operator's key for the [admin API](../api/admin.md) (`X-Admin-Key`); blank turns the admin API off |
| `ENFORCE_STRONG_SECRETS` | `false` | every service — `true` makes a service refuse to start while it uses a committed development secret (JWT key, vault master key, webhook key, internal token, admin key) or one that is too short; `false` logs a warning |
| `REDIS_PASSWORD` | empty (none) | Redis (set it where you run `docker compose`) and every service — Redis requires it when set |
| `REDIS_MAXMEMORY` | `768mb` | Redis only (set where you run `docker compose`). With `--maxmemory-policy volatile-lru`, past the limit Redis evicts only keys that expire — idempotency, rate-limit, refresh-token and lock keys — and never the webhook retry queue, which has no TTL. Idempotency keys are also backed by a unique index in the database, so an evicted key is not a duplicate. Unbounded, Redis grew past 1 GB under the load test |
| `JWT_RATE_LIMIT_PER_MINUTE`, `PUBLIC_AUTH_RATE_LIMIT_PER_MINUTE`, `MAX_FAILED_AUTH_PER_MINUTE` | `600`, `120`, `30` | the gateway — per merchant for JWT traffic, per client address on signup/login, and failed authentications per client address ([rate limits](../api/idempotency-and-rate-limits.md#rate-limits)) |
| `CLIENT_IP_HEADER` | empty | the gateway — the header (e.g. `X-Forwarded-For`) holding the real client address when behind a proxy; empty uses the socket's address |
| `GATEWAY_TLS_ENABLED`, `GATEWAY_TLS_KEYSTORE`, `GATEWAY_TLS_KEYSTORE_PASSWORD`, `GATEWAY_TLS_KEYSTORE_TYPE` | `false`, empty, empty, `PKCS12` | the gateway — serve HTTPS from a keystore |
| `payment.simulator.capture.failure-rate`, `payment.simulator.capture.failures-before-success` | `0`, `1` | payment-service (`config-repo/payment-service.yaml`) — the share of payments whose capture the simulated acquirer refuses besides the test values, and how many captures of such a payment are refused before one goes through. See [capture failures](../api/mock-acquirer.md#3-capture--the-acquirer-can-refuse-it) |
| `settlement.simulator.*`, `settlement.transfer-timeout-minutes` | `NORMAL`, delays 2–12 s, `95`, refuse `0`; `120` | operations-service (`config-repo/operations-service.yaml`) — how the simulated bank answers a payout (`chaos-mode`, `min-delay-seconds`, `max-delay-seconds`, `success-rate`, `refuse-rate`) and how long an unanswered transfer waits before it is failed. See [the payout simulator](../api/mock-acquirer.md#4-the-payout-simulator) |
| `SETTLEMENT_FEE_RATE`, `SETTLEMENT_GST_RATE`, `SETTLEMENT_HOLD_DAYS` | `0.02`, `0.18`, `0` | operations-service — the platform fee (of what the merchant kept after refunds), the GST on the fee, and how many days a payment is held before it is paid out (T+N). Merchants settled at once, the per-run payment cap and the page size are `settlement.*` in `operations-service.yaml` |
| `OUTBOX_RETENTION_DAYS` | `7` | payment-service, operations-service — published outbox rows older than this are deleted |
| `WEBHOOK_ALLOW_PRIVATE_TARGETS` | `true` | merchant-service (when a config is saved) and operations-service (at delivery) — `true` lets a webhook URL point at localhost or a private address and use plain `http`; `false` (set it in any shared environment) requires `https` and a public address. Link-local and cloud-metadata addresses are refused either way |
| `ZIPKIN_URL` | `http://localhost:9411/api/v2/spans` | every service — where spans are sent ([tracing](../observability/tracing.md)) |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | every service — share of requests traced; lower it for load tests |
| `MANAGEMENT_PORT` | `9081` | the gateway — its Actuator port, separate from the public `8080` |
| `SERVER_ADDRESS` | `127.0.0.1` | every service, the config server and Eureka — the interface they listen on. Loopback by default so nothing on your network can reach a service directly (see [the security model](../architecture/security-model.md#network-exposure)); `0.0.0.0` on Kubernetes, or on the gateway alone to demo to another machine |
| `EUREKA_INSTANCE_IP` | `127.0.0.1` | every service — the address it registers with Eureka. Must match where it listens |
| `MAX_REQUEST_BODY_BYTES` | `1048576` | the gateway — the largest request body it accepts (`413 REQUEST_TOO_LARGE`) |
| `GRAFANA_ADMIN_PASSWORD` | `payflo-dev-only-change-me` | Grafana (set where you run `docker compose` for the observability stack) — the `admin` login. Anonymous viewing is off |
| `VAULT_VELOCITY_ENABLED`, `VAULT_TOKENIZE_PER_MINUTE`, `VAULT_TOKENIZE_PER_HOUR` | `true`, `30`, `600` | vault-service — how many cards one merchant may tokenize ([card-testing protection](../api/idempotency-and-rate-limits.md#velocity-limits-and-card-testing)); set `VAULT_VELOCITY_ENABLED=false` for a test that tokenizes a lot |
| `CARD_VELOCITY_ENABLED`, `payment.velocity.card.*` | `true`; 10 min window, 20 failures, 0.5 share, 5 per order | payment-service (`config-repo/payment-service.yaml`) — refuses card payments from a merchant whose card payments mostly fail, and a sixth card payment on one order |
| `API_KEY_RATE_LIMIT_PER_MINUTE` | `200` | the gateway — requests per minute per API key; raise it for [load tests](../load-testing/running.md) |

The development defaults for the three secrets are committed on purpose so the stack starts with no setup. Never use them for anything shared; see [design trade-offs](../architecture/trade-offs.md#secrets-have-development-defaults).

## Settings worth knowing

| Setting | File | Value | Effect |
|---|---|---|---|
| `app.rate-limit.method` | `api-gateway-service.yaml` | `fixed` | Which of the four `RateLimiter` implementations is active. **Must be set** — with no value, no limiter bean exists and the gateway fails to start |
| `app.rate-limit.use-case.api-key.requests-per-minute` | `api-gateway-service.yaml` | `200` (`API_KEY_RATE_LIMIT_PER_MINUTE`) | Per-API-key limit |
| `app.gateway.proxy.max-connections-per-route`, `…-total` | `ProxyHttpClientConfig` defaults | `200`, `1000` | The gateway's connection pool to downstream services — the HTTP client's own default is 5 per route ([why](../practices/gotchas/microservices.md#the-gateways-proxy-pool-allows-5-connections-per-route)) |
| `app.security.public-routes` | `api-gateway-service.yaml` | signup, login, `/webhook/**` | Routes the gateway lets through without credentials. The gateway's own health and metrics are on its management port, outside this filter |
| `payment.simulator.*` | `payment-service.yaml` | poll every 1 s; `batch-size: 50`, `concurrency: 4` (batches at once); per-method delay and success rate; `chaos-mode: NORMAL` | How the simulated bank resolves authorizations — see [mock acquirer](../api/mock-acquirer.md). `concurrency` is how many callbacks run at once; each holds a database connection |
| `app.webhook.target-cache-ttl-seconds` | `operations-service.yaml` | `30` | How long the webhook consumer remembers a merchant's targets before asking merchant-service again |
| `app.webhook.delivery.poll-batch-size`, `…concurrency` | `operations-service.yaml` | `200`, `8` | The most webhook events taken off the retry queue at a time, and how many are delivered at once (each holds a database connection for two short transactions, so keep it under the pool) |
| `spring.task.scheduling.pool.size` | `payment-service.yaml`, `operations-service.yaml` | `8` | Scheduler threads, so one scheduled job doesn't wait behind another |
| `payment.order.default-order-expiry-minutes` | `payment-service.yaml` | `15` | `expiresAt` when an order doesn't send one |

Next: [first-time setup](setup.md).
