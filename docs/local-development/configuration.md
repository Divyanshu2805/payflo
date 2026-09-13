# Configuration

## Where settings live

Every service's own `application.yaml` holds only its name and one line:

```yaml
spring.config.import: configserver:${CONFIG_SERVER_URL:http://localhost:8888}
```

Everything else — ports, datasources, Kafka, Redis, routes, resilience settings, simulator knobs, secrets with development defaults — lives in [`microservices/config-repo/`](../../microservices/config-repo), served by config-service's native backend:

| File | Applies to |
|---|---|
| `application.yaml` | Every service: the Eureka URL, Redis, the JWT secret, virtual threads, actuator exposure |
| `<service>.yaml` | One service: its port, datasource, Kafka topics, Feign/Resilience4j instances and domain settings |
| `application-k8s.yaml`, `<service>-k8s.yaml` | The same, when a service runs with the `k8s` profile on Kubernetes — see [deployment configuration](../deployment.md) |

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
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | `localhost`, `6380`, empty | the gateway and every business service (idempotency, caches, the retry queue, ShedLock) |
| `KAFKA_BROKERS` | `localhost:29092` | payment, operations |
| `JWT_SECRET` | a development-only value | merchant-service (signs) and the gateway (verifies) — must be identical |
| `VAULT_MASTER_KEY` | a development-only value | vault-service only |
| `WEBHOOK_SECRET_KEY` | a development-only value | merchant-service only — encrypts webhook signing secrets |

The development defaults for the three secrets are committed on purpose so the stack starts with no setup. Never use them for anything shared; see [known gaps](../known-gaps/not-yet-built.md#security).

## Settings worth knowing

| Setting | File | Value | Effect |
|---|---|---|---|
| `app.rate-limit.method` | `api-gateway-service.yaml` | `fixed` | Which of the four `RateLimiter` implementations is active. **Must be set** — with no value, no limiter bean exists and the gateway fails to start |
| `app.rate-limit.use-case.api-key.requests-per-minute` | `api-gateway-service.yaml` | `200` | Per-API-key limit |
| `app.security.public-routes` | `api-gateway-service.yaml` | signup, login, `/webhook/**`, health | Routes the gateway lets through without credentials |
| `payment.simulator.*` | `payment-service.yaml` | poll every 5 s; per-method delay and success rate; `chaos-mode: NORMAL` | How the simulated bank resolves authorizations — see [mock acquirer](../api/mock-acquirer.md) |
| `payment.order.default-order-expiry-minutes` | `payment-service.yaml` | `30` | `expiresAt` when an order doesn't send one |

Next: [first-time setup](setup.md).
