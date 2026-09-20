# Observability

How to see what the running system is doing: where a request went, how long each hop took, and whether the services are meeting their targets.

| Signal | How it's produced | Where to look |
|---|---|---|
| **Traces** | Micrometer Tracing (Brave) in every service, reported to Zipkin | [Tracing](tracing.md) |
| **Metrics** | Micrometer, exposed at `/actuator/prometheus` on every service and scraped by Prometheus | [Metrics](metrics.md) |
| **Logs** | Standard output; every line carries the trace and span id | [Tracing → logs](tracing.md#logs) |

All of it is configured once, in `config-repo/application.yaml`, and wired into every service by `common-lib` — a service gets tracing and metrics by depending on `common-lib`, with no code of its own.

## Where each service exposes Actuator

| Service | Actuator (`/actuator/health`, `/actuator/prometheus`) |
|---|---|
| merchant-service, payment-service, vault-service, operations-service | their normal port — `8081`–`8084` |
| api-gateway-service | **the management port `9081`** (`MANAGEMENT_PORT`), not its public `8080` |

The gateway's Actuator lives on a separate port on purpose: the public listener runs `GatewayAuthFilter` and is the one port exposed to clients, so metrics and health there would either need credentials or be public. On the management port neither applies, and on Kubernetes it's only reachable inside the cluster.

## Pages

- [Tracing](tracing.md) — what is traced, following one request across services, sampling, logs.
- [Metrics](metrics.md) — what is measured and the queries for throughput, latency percentiles and availability.
