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
| `MERCHANT_DB_POOL_SIZE`, `PAYMENT_DB_POOL_SIZE`, `VAULT_DB_POOL_SIZE`, `OPERATIONS_DB_POOL_SIZE` | `20`, `40`, `10`, `10` | each business service's database connection pool — keep the sum under PostgreSQL's `max_connections` |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | `localhost`, `6380`, empty | the gateway and every business service (idempotency, caches, the retry queue, ShedLock) |
| `KAFKA_BROKERS` | `localhost:29092` | payment, operations |
| `JWT_SECRET` | a development-only value | merchant-service (signs) and the gateway (verifies) — must be identical |
| `VAULT_MASTER_KEY` | a development-only value | vault-service only |
| `WEBHOOK_SECRET_KEY` | a development-only value | merchant-service only — encrypts webhook signing secrets |
| `WEBHOOK_ALLOW_PRIVATE_TARGETS` | `true` | merchant-service (when a config is saved) and operations-service (at delivery) — `true` lets a webhook URL point at localhost or a private address and use plain `http`; `false` (set it in any shared environment) requires `https` and a public address. Link-local and cloud-metadata addresses are refused either way |
| `ZIPKIN_URL` | `http://localhost:9411/api/v2/spans` | every service — where spans are sent ([tracing](../observability/tracing.md)) |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | every service — share of requests traced; lower it for load tests |
| `MANAGEMENT_PORT` | `9081` | the gateway — its Actuator port, separate from the public `8080` |
| `API_KEY_RATE_LIMIT_PER_MINUTE` | `200` | the gateway — requests per minute per API key; raise it for [load tests](../load-testing/running.md) |

The development defaults for the three secrets are committed on purpose so the stack starts with no setup. Never use them for anything shared; see [known gaps](../known-gaps/not-yet-built.md#security).

## Settings worth knowing

| Setting | File | Value | Effect |
|---|---|---|---|
| `app.rate-limit.method` | `api-gateway-service.yaml` | `fixed` | Which of the four `RateLimiter` implementations is active. **Must be set** — with no value, no limiter bean exists and the gateway fails to start |
| `app.rate-limit.use-case.api-key.requests-per-minute` | `api-gateway-service.yaml` | `200` (`API_KEY_RATE_LIMIT_PER_MINUTE`) | Per-API-key limit |
| `app.gateway.proxy.max-connections-per-route`, `…-total` | `ProxyHttpClientConfig` defaults | `200`, `1000` | The gateway's connection pool to downstream services — the HTTP client's own default is 5 per route ([why](../practices/gotchas/microservices.md#the-gateways-proxy-pool-allows-5-connections-per-route)) |
| `app.security.public-routes` | `api-gateway-service.yaml` | signup, login, `/webhook/**` | Routes the gateway lets through without credentials. The gateway's own health and metrics are on its management port, outside this filter |
| `payment.simulator.*` | `payment-service.yaml` | poll every 5 s; `concurrency: 16`; per-method delay and success rate; `chaos-mode: NORMAL` | How the simulated bank resolves authorizations — see [mock acquirer](../api/mock-acquirer.md). `concurrency` is how many callbacks run at once; each holds a database connection |
| `app.webhook.target-cache-ttl-seconds` | `operations-service.yaml` | `30` | How long the webhook consumer remembers a merchant's targets before asking merchant-service again |
| `spring.task.scheduling.pool.size` | `payment-service.yaml`, `operations-service.yaml` | `4` | Scheduler threads, so one scheduled job doesn't wait behind another |
| `payment.order.default-order-expiry-minutes` | `payment-service.yaml` | `30` | `expiresAt` when an order doesn't send one |

Next: [first-time setup](setup.md).
